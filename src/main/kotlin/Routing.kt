package com.example

import com.example.db.AssignmentsRepo
import com.example.db.Db
import com.example.db.IdempotencyRepo
import com.example.db.MessagesRepo
import com.example.db.SessionsRepo
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.application
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.util.UUID

fun Application.configureRouting(
    llm: LlmClient,
    assignments: AssignmentsRepo,
    sessions: SessionsRepo,
    messages: MessagesRepo,
    idem: IdempotencyRepo,
    db: Db
) {
    val json = Json { ignoreUnknownKeys = true }

    // How many last messages we give to the LLM as conversation context.
    val llmContextLimit = 60

    routing {
        head("/") { call.respond(HttpStatusCode.OK) }
        get("/") { call.respondText("OK") }

        get("/health") {
            val provider = System.getenv("LLM_PROVIDER")?.lowercase() ?: "ollama"

            val startedAt = runCatching {
                application.attributes[AppAttributes.StartedAtMs]
            }.getOrElse {
                System.currentTimeMillis()
            }

            val uptimeSec = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)

            call.respond(
                HealthResp(
                    status = "ok",
                    provider = provider,
                    uptimeSec = uptimeSec
                )
            )
        }

        // ✅ DB-specific health check
        get("/health/db") {
            val ok = db.query { conn ->
                conn.prepareStatement("select 1").use { ps ->
                    ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) == 1 }
                }
            }
            call.respond(mapOf("ok" to ok))
        }

        route("/v1") {

            get("/llm/ping") {
                val provider = System.getenv("LLM_PROVIDER")?.lowercase() ?: "ollama"
                val t0 = System.nanoTime()

                val text = llm.generateOpener(topic = "Ping", vocab = listOf("hello"), level = null)

                val tookMs = (System.nanoTime() - t0) / 1_000_000

                application.log.info(
                    "llm.generateOpener ok requestId=${call.callId} provider=$provider tookMs=$tookMs"
                )

                call.respond(
                    LlmPingResp(
                        status = "ok",
                        provider = provider,
                        sample = text.take(200),
                        tookMs = tookMs
                    )
                )
            }

            post("/assignments") {
                val ip = call.clientIp()
                val rlOk = RateLimiter.allow(
                    key = "v1.assignments.create.ip=$ip",
                    policy = RateLimiter.Policy(limit = 10, windowMs = 60_000)
                )
                if (!rlOk) {
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ApiErrorEnvelope(ApiError(ApiErrorCodes.RATE_LIMIT, "Too many requests"))
                    )
                    return@post
                }

                if (!TeacherAuth.isAllowed(call)) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiErrorEnvelope(ApiError(ApiErrorCodes.AUTH_ERROR, "Unauthorized"))
                    )
                    return@post
                }

                val req = call.receive<CreateAssignmentReq>()
                val topic = req.topic.trim()
                val vocab = req.vocab.map { it.trim() }.filter { it.isNotBlank() }
                val level = req.level?.trim()?.ifBlank { null }

                // joinKey generation: same alphabet as before, but must be unique in DB
                val joinKey = generateJoinKey()

                val a = Assignment(
                    id = UUID.randomUUID().toString(),
                    joinKey = joinKey,
                    topic = topic,
                    vocab = vocab,
                    level = level
                )

                // If collision (unique join_key), retry a few times
                var inserted = false
                repeat(10) { attempt ->
                    val keyToTry = if (attempt == 0) a.joinKey else generateJoinKey()
                    val cur = a.copy(joinKey = keyToTry)
                    inserted = runCatching {
                        assignments.insert(cur)
                        true
                    }.getOrElse { e ->
                        // naive check: unique violation -> retry, else rethrow
                        val msg = e.message.orEmpty().lowercase()
                        if (msg.contains("duplicate key") || msg.contains("unique")) false else throw e
                    }
                    if (inserted) {
                        call.respond(
                            status = HttpStatusCode.Created,
                            message = CreateAssignmentResp(
                                assignmentId = cur.id,
                                joinKey = cur.joinKey
                            )
                        )
                        return@post
                    }
                }

                throw IllegalStateException("Failed to generate unique joinKey after retries")
            }

            get("/assignments/{id}") {
                val assignmentId = call.parameters["id"]
                    ?: throw IllegalArgumentException("Missing assignment id")

                val a = assignments.getById(UUID.fromString(assignmentId))
                    ?: run {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorEnvelope(
                                ApiError(
                                    code = ApiErrorCodes.ASSIGNMENT_NOT_FOUND,
                                    message = "Assignment not found"
                                )
                            )
                        )
                        return@get
                    }

                call.respond(
                    AssignmentDto(
                        assignmentId = a.id,
                        joinKey = a.joinKey,
                        topic = a.topic,
                        vocab = a.vocab,
                        level = a.level
                    )
                )
            }

            post("/sessions/open") {
                val ip = call.clientIp()
                val rlOk = RateLimiter.allow(
                    key = "v1.sessions.open.ip=$ip",
                    policy = RateLimiter.Policy(limit = 30, windowMs = 60_000)
                )
                if (!rlOk) {
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ApiErrorEnvelope(ApiError(ApiErrorCodes.RATE_LIMIT, "Too many requests"))
                    )
                    return@post
                }

                val req = call.receive<OpenSessionReq>()
                val joinKey = req.joinKey.trim().uppercase()

                val a = assignments.getByJoinKey(joinKey)
                    ?: run {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorEnvelope(
                                ApiError(
                                    code = ApiErrorCodes.INVALID_JOIN_KEY,
                                    message = "Invalid joinKey"
                                )
                            )
                        )
                        return@post
                    }

                val sessionId = sessions.createFromAssignment(
                    assignmentId = UUID.fromString(a.id),
                    joinKey = a.joinKey,
                    topic = a.topic,
                    vocab = a.vocab,
                    level = a.level
                )

                val provider = System.getenv("LLM_PROVIDER")?.lowercase() ?: "ollama"
                val t0 = System.nanoTime()
                val opener = llm.generateOpener(
                    topic = a.topic,
                    vocab = a.vocab,
                    level = a.level
                ).ifBlank {
                    "Let’s start with something simple—what comes to mind first?"
                }
                val tookMs = (System.nanoTime() - t0) / 1_000_000

                application.log.info(
                    "llm.generateOpener ok requestId=${call.callId} sessionId=$sessionId provider=$provider tookMs=$tookMs"
                )

                // persist opener as seq=1
                db.tx { conn ->
                    messages.appendInTx(conn, sessionId, role = "tutor", content = opener)
                }

                call.respond(
                    status = HttpStatusCode.Created,
                    message = OpenSessionResp(
                        sessionId = sessionId.toString(),
                        assignmentId = a.id,
                        joinKey = a.joinKey,
                        topic = a.topic,
                        messages = listOf(MessageDto("tutor", opener))
                    )
                )
            }

            post("/sessions/{id}/messages") {
                val sessionIdStr = call.parameters["id"]
                    ?: throw IllegalArgumentException("Missing session id")
                val sessionId = UUID.fromString(sessionIdStr)

                val ip = call.clientIp()
                val rlOk = RateLimiter.allow(
                    key = "v1.sessions.messages.ip=$ip.session=$sessionIdStr",
                    policy = RateLimiter.Policy(limit = 60, windowMs = 60_000)
                )
                if (!rlOk) {
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ApiErrorEnvelope(ApiError(ApiErrorCodes.RATE_LIMIT, "Too many requests"))
                    )
                    return@post
                }

                // Ensure session exists (cheap)
                val sessionSnapshotBefore = sessions.getSessionSnapshot(sessionId, messageLimit = llmContextLimit)
                    ?: run {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorEnvelope(
                                ApiError(
                                    code = ApiErrorCodes.SESSION_NOT_FOUND,
                                    message = "Session not found"
                                )
                            )
                        )
                        return@post
                    }

                val idemKey = call.request.headers["Idempotency-Key"]?.trim().orEmpty().ifBlank { null }

                // 1) Fast path: already computed
                if (idemKey != null) {
                    val cached = idem.get(sessionId, idemKey)
                    if (cached != null) {
                        call.respond(cached)
                        return@post
                    }
                }

                val req = call.receive<StudentMessageReq>()
                val studentText = req.text.trim()

                // 2) Claim (and only then persist student message) inside ONE tx
                if (idemKey != null) {
                    val claimed = db.tx { conn ->
                        val ok = idem.claimInTx(conn, sessionId, idemKey)
                        if (!ok) return@tx false

                        messages.appendInTx(conn, sessionId, role = "student", content = studentText)
                        true
                    }

                    if (!claimed) {
                        // Someone else already claimed it; return cached if already completed.
                        val cached = idem.get(sessionId, idemKey)
                        if (cached != null) {
                            call.respond(cached)
                            return@post
                        }

                        // Still pending (or fresh pending). Let client retry.
                        call.respond(
                            HttpStatusCode.Conflict,
                            ApiErrorEnvelope(
                                ApiError(
                                    code = ApiErrorCodes.BAD_REQUEST,
                                    message = "Request with this Idempotency-Key is already in progress"
                                )
                            )
                        )
                        return@post
                    }
                } else {
                    // No idempotency: just persist student message
                    db.tx { conn ->
                        messages.appendInTx(conn, sessionId, role = "student", content = studentText)
                    }
                }

                // Snapshot for LLM should include the just-inserted student message
                val sessionSnapshot = sessions.getSessionSnapshot(sessionId, messageLimit = llmContextLimit)
                    ?: sessionSnapshotBefore

                // 3) Compute coverage from persisted history (student corpus)
                val studentCorpus = messages.listStudentContents(sessionId).joinToString("\n")
                val (usedEver, missingEver) = computeVocabCoverage(studentCorpus, sessionSnapshot.vocab)
                val studentTurns = messages.countByRole(sessionId, role = "student")

                // 4) Call LLM outside tx
                val provider = System.getenv("LLM_PROVIDER")?.lowercase() ?: "ollama"
                val t0 = System.nanoTime()

                val tutorTextFromLlm = llm.tutorReply(
                    session = sessionSnapshot,
                    studentText = studentText,
                    used = usedEver,
                    missing = missingEver
                )

                val tookMs = (System.nanoTime() - t0) / 1_000_000
                application.log.info(
                    "llm.tutorReply ok requestId=${call.callId} sessionId=$sessionId provider=$provider tookMs=$tookMs"
                )

                val shouldShowHint = missingEver.isNotEmpty() && studentTurns >= 2
                val hint = if (shouldShowHint) pickHint(missingEver) else null

                val tutor = TutorMessageResp(
                    tutorText = tutorTextFromLlm,
                    hint = hint,
                    vocabUsed = usedEver,
                    vocabMissing = missingEver
                )

                // 5) Persist tutor message + complete idempotency (if used) in one tx
                db.tx { conn ->
                    messages.appendInTx(conn, sessionId, role = "tutor", content = tutor.tutorText)
                    if (idemKey != null) {
                        idem.completeInTx(conn, sessionId, idemKey, tutor)
                    }
                }

                call.respond(tutor)
            }

            get("/sessions/{id}") {
                val sessionIdStr = call.parameters["id"]
                    ?: throw IllegalArgumentException("Missing session id")
                val sessionId = UUID.fromString(sessionIdStr)

                // Full history for UI
                val session = sessions.getSessionSnapshot(sessionId, messageLimit = null)
                    ?: run {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorEnvelope(
                                ApiError(
                                    code = ApiErrorCodes.SESSION_NOT_FOUND,
                                    message = "Session not found"
                                )
                            )
                        )
                        return@get
                    }

                call.respond(
                    HistoryResp(
                        sessionId = session.id,
                        assignmentId = session.assignmentId,
                        joinKey = session.joinKey,
                        topic = session.topic,
                        vocab = session.vocab,
                        messages = session.messages.map { MessageDto(it.role, it.content) }
                    )
                )
            }

            get("/sessions") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                if (limit !in 1..100) throw IllegalArgumentException("limit must be between 1 and 100")
                if (offset < 0) throw IllegalArgumentException("offset must be >= 0")

                val items = sessions.listSummaries(limit, offset)

                call.respond(ListSessionsResp(items = items, limit = limit, offset = offset))
            }

            delete("/sessions/{id}") {
                val sessionIdStr = call.parameters["id"]
                    ?: throw IllegalArgumentException("Missing session id")
                val sessionId = UUID.fromString(sessionIdStr)

                val deleted = sessions.delete(sessionId)
                if (!deleted) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiErrorEnvelope(
                            ApiError(
                                code = ApiErrorCodes.SESSION_NOT_FOUND,
                                message = "Session not found"
                            )
                        )
                    )
                    return@delete
                }

                call.respond(HttpStatusCode.NoContent)
            }

            route("{...}") {
                handle {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiErrorEnvelope(
                            ApiError(
                                code = ApiErrorCodes.NOT_FOUND,
                                message = "Not found"
                            )
                        )
                    )
                }
            }
        }
    }
}

/**
 * JoinKey generator (same alphabet as your old store).
 */
private fun generateJoinKey(length: Int = 6): String {
    val alphabet = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
    val rng = java.security.SecureRandom()
    return buildString(length) {
        repeat(length) { append(alphabet[rng.nextInt(alphabet.length)]) }
    }
}

/**
 * Phrase-aware покрытие лексики:
 * - если элемент без пробелов => матч по токенам
 * - если элемент с пробелами => матч фразы в нормализованном тексте
 */
private fun computeVocabCoverage(text: String, vocab: List<String>): Pair<List<String>, List<String>> {
    val normalized = normalizeForMatch(text)

    val tokens = normalized
        .split(Regex("""[^a-z0-9']+"""))
        .filter { it.isNotBlank() }
        .toSet()

    fun containsPhrase(phrase: String): Boolean {
        val p = normalizeForMatch(phrase)
        if (p.isBlank()) return false
        val hay = " $normalized "
        val needle = " $p "
        return hay.contains(needle)
    }

    val used = vocab.filter { v ->
        val vv = v.trim()
        if (vv.contains(" ")) containsPhrase(vv) else normalizeForMatch(vv) in tokens
    }

    val missing = vocab.filterNot { v -> used.any { it.equals(v, ignoreCase = true) } }
    return used to missing
}

private fun normalizeForMatch(s: String): String {
    return s.lowercase()
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""[^a-z0-9' ]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun pickHint(missingEver: List<String>): String? {
    if (missingEver.isEmpty()) return null

    val priority = listOf("because", "however", "recommend")
    val chosen = missingEver.minByOrNull { w ->
        val idx = priority.indexOf(w.trim().lowercase())
        if (idx >= 0) idx else 999
    } ?: return null

    return when (chosen.trim().lowercase()) {
        "however" -> "Try: \"I liked it. However, ...\""
        "because" -> "Try: \"... because ...\""
        "recommend" -> "Try: \"I recommend ...\""
        else -> "Try to use: $chosen"
    }
}
