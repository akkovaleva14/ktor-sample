package com.example.adapters.http

import com.example.adapters.http.dto.*
import com.example.adapters.http.errors.ApiError
import com.example.adapters.http.errors.ApiErrorCodes
import com.example.adapters.http.errors.ApiErrorEnvelope
import com.example.adapters.http.util.clientIp
import com.example.app.AppAttributes
import com.example.core.model.TutorReply
import com.example.core.ports.LlmPingResult
import com.example.core.ports.LlmPort
import com.example.core.ports.TxPort
import com.example.core.usecase.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*

/**
 * HTTP adapter.
 *
 * Здесь только:
 * - парсинг HTTP запроса
 * - маппинг результатов use case -> HTTP responses/DTO
 * - логирование / requestId
 *
 * Вся бизнес-логика живёт в core/usecase + core/policy.
 */
object Routing {

    fun install(
        app: Application,
        tx: TxPort,
        llm: LlmPort,
        createAssignment: CreateAssignmentUseCase,
        getAssignment: GetAssignmentUseCase,
        openSession: OpenSessionUseCase,
        postStudentMessage: PostStudentMessageUseCase,
        getSession: GetSessionUseCase,
        listSessions: ListSessionsUseCase,
        deleteSession: DeleteSessionUseCase
    ) {
        app.routing {
            head("/") { call.respond(HttpStatusCode.OK) }
            get("/") { call.respondText("OK") }

            get("/health") {
                val provider = System.getenv("LLM_PROVIDER")?.lowercase() ?: "ollama"

                val startedAt = runCatching {
                    app.attributes[AppAttributes.StartedAtMs]
                }.getOrElse { System.currentTimeMillis() }

                val uptimeSec = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)

                call.respond(
                    HealthResp(
                        status = "ok",
                        provider = provider,
                        uptimeSec = uptimeSec
                    )
                )
            }

            // DB-specific health check (kept simple)
            get("/health/db") {
                val ok = tx.tx { true } // If tx can run, DB is up. (Deeper check can be in DB adapter.)
                call.respond(mapOf("ok" to ok))
            }

            route("/v1") {

                /**
                 * LLM health check (provider-level).
                 *
                 * Backward compatible with the previous probe:
                 *   curl -sS -i "$BASE/v1/llm/ping"
                 *
                 * Semantics:
                 * - 200 OK when provider is reachable & ready
                 * - 503 Service Unavailable when provider check fails
                 */
                get("/llm/ping") {
                    when (val res = llm.ping()) {
                        is LlmPingResult.Ok -> call.respond(
                            HttpStatusCode.OK,
                            mapOf(
                                "ok" to true,
                                "provider" to res.provider,
                                "latencyMs" to res.latency?.inWholeMilliseconds,
                                "details" to res.details
                            )
                        )

                        is LlmPingResult.Fail -> call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf(
                                "ok" to false,
                                "provider" to res.provider,
                                "reason" to res.reason,
                                "httpStatus" to res.httpStatus
                            )
                        )
                    }
                }

                post("/assignments") {
                    val ip = call.clientIp()

                    val authHeader = call.request.headers[HttpHeaders.Authorization].orEmpty().trim()
                    val bearer = authHeader
                        .takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                        ?.removePrefix("Bearer")
                        ?.trim()
                        ?.ifBlank { null }

                    val req = call.receive<CreateAssignmentReq>()
                    val res = createAssignment.execute(
                        CreateAssignmentUseCase.Input(
                            presentedBearerToken = bearer,
                            ip = ip,
                            topic = req.topic,
                            vocab = req.vocab,
                            level = req.level
                        )
                    )

                    when (res) {
                        CreateAssignmentUseCase.Result.RateLimited -> call.respond(
                            HttpStatusCode.TooManyRequests,
                            ApiErrorEnvelope(ApiError(ApiErrorCodes.RATE_LIMIT, "Too many requests"))
                        )

                        CreateAssignmentUseCase.Result.Unauthorized -> call.respond(
                            HttpStatusCode.Unauthorized,
                            ApiErrorEnvelope(ApiError(ApiErrorCodes.AUTH_ERROR, "Unauthorized"))
                        )

                        is CreateAssignmentUseCase.Result.Created -> call.respond(
                            status = HttpStatusCode.Created,
                            message = CreateAssignmentResp(
                                assignmentId = res.assignment.id.toString(),
                                joinKey = res.assignment.joinKey
                            )
                        )
                    }
                }

                get("/assignments/{id}") {
                    val assignmentId = call.parameters["id"]
                        ?: throw IllegalArgumentException("Missing assignment id")

                    when (val res = getAssignment.execute(java.util.UUID.fromString(assignmentId))) {
                        GetAssignmentUseCase.Result.NotFound -> call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorEnvelope(
                                ApiError(
                                    code = ApiErrorCodes.ASSIGNMENT_NOT_FOUND,
                                    message = "Assignment not found"
                                )
                            )
                        )

                        is GetAssignmentUseCase.Result.Found -> {
                            val a = res.assignment
                            call.respond(
                                AssignmentDto(
                                    assignmentId = a.id.toString(),
                                    joinKey = a.joinKey,
                                    topic = a.topic,
                                    vocab = a.vocab,
                                    level = a.level
                                )
                            )
                        }
                    }
                }

                post("/sessions/open") {
                    val ip = call.clientIp()

                    val req = call.receive<OpenSessionReq>()
                    val res = openSession.execute(OpenSessionUseCase.Input(ip = ip, joinKey = req.joinKey))

                    when (res) {
                        OpenSessionUseCase.Result.RateLimited -> call.respond(
                            HttpStatusCode.TooManyRequests,
                            ApiErrorEnvelope(ApiError(ApiErrorCodes.RATE_LIMIT, "Too many requests"))
                        )

                        OpenSessionUseCase.Result.InvalidJoinKey -> call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorEnvelope(
                                ApiError(
                                    code = ApiErrorCodes.INVALID_JOIN_KEY,
                                    message = "Invalid joinKey"
                                )
                            )
                        )

                        is OpenSessionUseCase.Result.Created -> {
                            val s = res.session
                            call.respond(
                                status = HttpStatusCode.Created,
                                message = OpenSessionResp(
                                    sessionId = s.id.toString(),
                                    assignmentId = s.assignmentId.toString(),
                                    joinKey = s.joinKey,
                                    topic = s.topic,
                                    messages = s.messages.map { MessageDto(it.role, it.content) }
                                )
                            )
                        }
                    }
                }

                post("/sessions/{id}/messages") {
                    val sessionIdStr = call.parameters["id"]
                        ?: throw IllegalArgumentException("Missing session id")
                    val sessionId = java.util.UUID.fromString(sessionIdStr)

                    val ip = call.clientIp()
                    val idemKey = call.request.headers["Idempotency-Key"]?.trim().orEmpty().ifBlank { null }

                    val req = call.receive<StudentMessageReq>()
                    val res = postStudentMessage.execute(
                        PostStudentMessageUseCase.Input(
                            ip = ip,
                            sessionId = sessionId,
                            studentText = req.text,
                            idempotencyKey = idemKey
                        )
                    )

                    when (res) {
                        PostStudentMessageUseCase.Result.RateLimited -> call.respond(
                            HttpStatusCode.TooManyRequests,
                            ApiErrorEnvelope(ApiError(ApiErrorCodes.RATE_LIMIT, "Too many requests"))
                        )

                        PostStudentMessageUseCase.Result.SessionNotFound -> call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorEnvelope(ApiError(ApiErrorCodes.SESSION_NOT_FOUND, "Session not found"))
                        )

                        PostStudentMessageUseCase.Result.ConflictInProgress -> call.respond(
                            HttpStatusCode.Conflict,
                            ApiErrorEnvelope(
                                ApiError(
                                    code = ApiErrorCodes.BAD_REQUEST,
                                    message = "Request with this Idempotency-Key is already in progress"
                                )
                            )
                        )

                        is PostStudentMessageUseCase.Result.Ok -> call.respond(res.reply.toHttpDto())
                    }
                }

                get("/sessions/{id}") {
                    val sessionIdStr = call.parameters["id"]
                        ?: throw IllegalArgumentException("Missing session id")
                    val sessionId = java.util.UUID.fromString(sessionIdStr)

                    when (val res = getSession.execute(sessionId)) {
                        GetSessionUseCase.Result.NotFound -> call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorEnvelope(ApiError(ApiErrorCodes.SESSION_NOT_FOUND, "Session not found"))
                        )

                        is GetSessionUseCase.Result.Found -> {
                            val s = res.session
                            call.respond(
                                HistoryResp(
                                    sessionId = s.id.toString(),
                                    assignmentId = s.assignmentId.toString(),
                                    joinKey = s.joinKey,
                                    topic = s.topic,
                                    vocab = s.vocab,
                                    messages = s.messages.map { MessageDto(it.role, it.content) }
                                )
                            )
                        }
                    }
                }

                get("/sessions") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                    val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                    val items = listSessions.execute(limit, offset)
                    call.respond(
                        ListSessionsResp(
                            items = items.map { s ->
                                SessionSummaryDto(
                                    sessionId = s.sessionId.toString(),
                                    assignmentId = s.assignmentId.toString(),
                                    joinKey = s.joinKey,
                                    topic = s.topic,
                                    vocab = s.vocab,
                                    messageCount = s.messageCount
                                )
                            },
                            limit = limit,
                            offset = offset
                        )
                    )
                }

                delete("/sessions/{id}") {
                    val sessionIdStr = call.parameters["id"]
                        ?: throw IllegalArgumentException("Missing session id")
                    val sessionId = java.util.UUID.fromString(sessionIdStr)

                    val deleted = deleteSession.execute(sessionId)
                    if (!deleted) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorEnvelope(ApiError(ApiErrorCodes.SESSION_NOT_FOUND, "Session not found"))
                        )
                        return@delete
                    }

                    call.respond(HttpStatusCode.NoContent)
                }

                route("{...}") {
                    handle {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorEnvelope(ApiError(ApiErrorCodes.NOT_FOUND, "Not found"))
                        )
                    }
                }
            }
        }
    }

    private fun TutorReply.toHttpDto(): TutorMessageResp =
        TutorMessageResp(
            tutorText = tutorText,
            hint = hint,
            vocabUsed = vocabUsed,
            vocabMissing = vocabMissing
        )
}
