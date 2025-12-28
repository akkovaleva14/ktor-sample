package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting(llm: LlmClient) {
    routing {
        get("/") {
            call.respondText("OK")
        }

        route("/v1") {

            // --------------------------
            // TEACHER: create assignment
            // --------------------------
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

            // --------------------------
            // STUDENT: open session by joinKey
            // --------------------------
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

                // LLM generates a gentle opener based on topic + vocab
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

            // --------------------------
            // CHAT: student sends message
            // --------------------------
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

                val (used, missing) = computeVocabCoverage(studentText, session.vocab)

                val tutorTextFromLlm = llm.tutorReply(
                    session = session,
                    studentText = studentText,
                    used = used,
                    missing = missing
                )

                // Hint policy: do NOT show immediately.
                // Show only after the student has had at least 2 turns and still misses vocab.
                val studentTurns = session.messages.count { it.role == "student" }
                val shouldShowHint = missing.isNotEmpty() && studentTurns >= 2

                val tutor = TutorMessageResp(
                    tutorText = tutorTextFromLlm,
                    hint = if (shouldShowHint) {
                        missing.firstOrNull()?.let { w ->
                            when (w.lowercase()) {
                                "however" -> "Try: \"I liked it. However, ...\""
                                "because" -> "Try: \"... because ...\""
                                "recommend" -> "Try: \"I recommend ...\""
                                else -> "Try to use: $w"
                            }
                        }
                    } else null,
                    vocabUsed = used,
                    vocabMissing = missing
                )

                session.messages += Msg(role = "tutor", content = tutor.tutorText)

                call.respond(tutor)
            }

            // --------------------------
            // HISTORY
            // --------------------------
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

            // --------------------------
            // LIST SESSIONS (admin/debug)
            // --------------------------
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

            // --------------------------
            // DELETE SESSION
            // --------------------------
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

            // --------------------------
            // FALLBACK: unknown /v1/* routes
            // --------------------------
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

private fun computeVocabCoverage(text: String, vocab: List<String>): Pair<List<String>, List<String>> {
    val tokens = text
        .lowercase()
        .split(Regex("""[^a-z']+"""))
        .filter { it.isNotBlank() }
        .toSet()

    val used = vocab.filter { it.lowercase() in tokens }
    val missing = vocab.filterNot { it.lowercase() in tokens }
    return used to missing
}
