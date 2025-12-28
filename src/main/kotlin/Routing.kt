package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting(llm: LlmClient) {
    routing {
        head("/") { call.respond(HttpStatusCode.OK) }
        get("/") { call.respondText("OK") }

        get("/health") {
            call.respond(
                mapOf(
                    "status" to "ok",
                    "provider" to (System.getenv("LLM_PROVIDER")?.lowercase() ?: "ollama")
                )
            )
        }

        route("/v1") {

            get("/llm/ping") {
                val provider = System.getenv("LLM_PROVIDER")?.lowercase() ?: "ollama"
                val text = llm.generateOpener(topic = "Ping", vocab = listOf("hello"), level = null)

                call.respond(
                    mapOf(
                        "status" to "ok",
                        "provider" to provider,
                        "sample" to text.take(200)
                    )
                )
            }

            post("/assignments") {
                val req = call.receive<CreateAssignmentReq>()
                val assignment = AssignmentStore.create(
                    topic = req.topic.trim(),
                    vocab = req.vocab.map { it.trim() }.filter { it.isNotBlank() },
                    level = req.level?.trim()?.ifBlank { null }
                )

                call.respond(
                    status = HttpStatusCode.Created,
                    message = CreateAssignmentResp(
                        assignmentId = assignment.id,
                        joinKey = assignment.joinKey
                    )
                )
            }

            get("/assignments/{id}") {
                val assignmentId = call.parameters["id"]
                    ?: throw IllegalArgumentException("Missing assignment id")

                val a = AssignmentStore.getById(assignmentId)
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
                val req = call.receive<OpenSessionReq>()
                val joinKey = req.joinKey.trim().uppercase()

                val a = AssignmentStore.getByJoinKey(joinKey)
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

                val session = SessionStore.createFromAssignment(a)

                val opener = llm.generateOpener(
                    topic = session.topic,
                    vocab = session.vocab,
                    level = session.level
                ).ifBlank {
                    "Let’s start with something simple—what comes to mind first?"
                }

                session.messages += Msg(role = "tutor", content = opener)

                call.respond(
                    status = HttpStatusCode.Created,
                    message = OpenSessionResp(
                        sessionId = session.id,
                        assignmentId = session.assignmentId,
                        joinKey = session.joinKey,
                        topic = session.topic,
                        messages = session.messages.map { MessageDto(it.role, it.content) }
                    )
                )
            }

            post("/sessions/{id}/messages") {
                val sessionId = call.parameters["id"]
                    ?: throw IllegalArgumentException("Missing session id")

                val session = SessionStore.get(sessionId)
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

                val req = call.receive<StudentMessageReq>()
                val studentText = req.text.trim()

                session.messages += Msg(role = "student", content = studentText)

                // coverage for latest message
                val (usedLatest, missingLatest) = computeVocabCoverage(studentText, session.vocab)

                // coverage across ALL student turns (avoid silly hints like "wake" after it was used earlier)
                val studentCorpus = session.messages
                    .asSequence()
                    .filter { it.role == "student" }
                    .joinToString("\n") { it.content }

                val (usedEver, _) = computeVocabCoverage(studentCorpus, session.vocab)
                val missingEver = session.vocab.filterNot { v -> usedEver.any { it.equals(v, ignoreCase = true) } }

                val tutorTextFromLlm = llm.tutorReply(
                    session = session,
                    studentText = studentText,
                    used = usedLatest,
                    missing = missingLatest
                )

                val studentTurns = session.messages.count { it.role == "student" }
                val shouldShowHint = missingEver.isNotEmpty() && studentTurns >= 2

                val hint = if (shouldShowHint) pickHint(missingEver) else null

                val tutor = TutorMessageResp(
                    tutorText = tutorTextFromLlm,
                    hint = hint,
                    vocabUsed = usedLatest,
                    vocabMissing = missingLatest
                )

                session.messages += Msg(role = "tutor", content = tutor.tutorText)

                call.respond(tutor)
            }

            get("/sessions/{id}") {
                val sessionId = call.parameters["id"]
                    ?: throw IllegalArgumentException("Missing session id")

                val session = SessionStore.get(sessionId)
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

                val items = SessionStore.list(limit, offset).map { s ->
                    SessionSummaryDto(
                        sessionId = s.id,
                        assignmentId = s.assignmentId,
                        joinKey = s.joinKey,
                        topic = s.topic,
                        vocab = s.vocab,
                        messageCount = s.messages.size
                    )
                }

                call.respond(ListSessionsResp(items = items, limit = limit, offset = offset))
            }

            delete("/sessions/{id}") {
                val sessionId = call.parameters["id"]
                    ?: throw IllegalArgumentException("Missing session id")

                val deleted = SessionStore.delete(sessionId)
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
 * Phrase-aware vocab coverage:
 * - single word vocab: token match
 * - multi-word vocab (contains space): substring match on normalized text with word boundaries-ish behavior
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

        // crude but effective boundaries: pad with spaces
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

/**
 * Prioritized hint selection:
 * - connectors first (because/however/recommend)
 * - then other items
 */
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
