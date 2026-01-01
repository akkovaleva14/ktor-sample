package com.example.app

import com.example.adapters.http.*
import com.example.adapters.http.Routing
import com.example.app.wiring.AppWiring
import com.example.core.policy.JoinKeyGenerator
import com.example.core.ports.LlmPort
import com.example.core.usecase.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.requestvalidation.ValidationResult
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.UUID

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"

    embeddedServer(Netty, port = port, host = host) {
        module()
    }.start(wait = true)
}

/**
 * Composition root приложения (Ktor).
 *
 * Важно: здесь нет бизнес-логики — только wiring + установка Ktor plugins + routing.
 */
fun Application.module(llmOverride: LlmPort? = null) {
    val log = this.log
    attributes.put(AppAttributes.StartedAtMs, System.currentTimeMillis())

    val dataSource = runCatching {
        Database.createDataSourceFromEnv()
    }.onFailure { e ->
        log.error("Failed to create DataSource from DATABASE_URL", e)
    }.getOrThrow()

    runCatching {
        Database.migrate(dataSource)
    }.onFailure { e ->
        log.error("Flyway migration failed", e)
    }.getOrThrow()

    log.info("DB connected and migrations applied")

    val wiring = AppWiring(log)
    val clients = wiring.createHttpClients()

    val (jdbcSession, tx) = wiring.createTx(dataSource)
    val dbPorts = wiring.createDbPorts(jdbcSession)

    val llm = wiring.createLlm(clients, llmOverride)
    val auth = wiring.createTeacherAuth()
    val rate = wiring.createRateLimiter()
    val clock = wiring.createClock()

    // --- Background cleanup: idempotency table TTL ---
    val idemCleanupEnabled = System.getenv("IDEMPOTENCY_CLEANUP_ENABLED")?.trim()?.lowercase()
        ?.let { it == "1" || it == "true" || it == "yes" } ?: true

    val idemTtlDays = System.getenv("IDEMPOTENCY_TTL_DAYS")?.trim()?.toLongOrNull()?.coerceAtLeast(1) ?: 7L
    val idemCleanupEveryMin = System.getenv("IDEMPOTENCY_CLEANUP_EVERY_MIN")?.trim()?.toLongOrNull()?.coerceAtLeast(1)
        ?: 60L

    val cleanupJob: Job? = if (idemCleanupEnabled) {
        log.info("Idempotency cleanup enabled: ttlDays=$idemTtlDays everyMin=$idemCleanupEveryMin")

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            delay(15_000)

            val ttl = Duration.ofDays(idemTtlDays)
            val periodMs = Duration.ofMinutes(idemCleanupEveryMin).toMillis()

            while (isActive) {
                try {
                    val deleted = dbPorts.idem.cleanupOlderThan(ttl)
                    if (deleted > 0) {
                        log.info("Idempotency cleanup: deleted=$deleted olderThanDays=$idemTtlDays")
                    }
                } catch (t: Throwable) {
                    log.warn("Idempotency cleanup failed: ${t.message}", t)
                }

                delay(periodMs)
            }
        }
    } else {
        log.info("Idempotency cleanup disabled by env IDEMPOTENCY_CLEANUP_ENABLED")
        null
    }

    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
        replyToHeader(HttpHeaders.XRequestId)
    }

    install(CallLogging) { mdc("requestId") { call -> call.callId } }

    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }

    install(RequestValidation) {
        validate<com.example.adapters.http.dto.CreateAssignmentReq> { req ->
            val topic = req.topic.trim()
            when {
                topic.isEmpty() -> ValidationResult.Invalid("topic must not be blank")
                topic.length > 200 -> ValidationResult.Invalid("topic too long (max 200 chars)")
                req.vocab.isEmpty() -> ValidationResult.Invalid("vocab must not be empty")
                req.vocab.size > 50 -> ValidationResult.Invalid("vocab too large (max 50)")
                req.vocab.any { it.trim().isEmpty() } -> ValidationResult.Invalid("vocab contains blank items")
                else -> ValidationResult.Valid
            }
        }

        validate<com.example.adapters.http.dto.OpenSessionReq> { req ->
            val key = req.joinKey.trim()
            when {
                key.isEmpty() -> ValidationResult.Invalid("joinKey must not be blank")
                key.length !in 4..12 -> ValidationResult.Invalid("joinKey length must be 4..12")
                else -> ValidationResult.Valid
            }
        }

        validate<com.example.adapters.http.dto.StudentMessageReq> { req ->
            val t = req.text.trim()
            when {
                t.isEmpty() -> ValidationResult.Invalid("text must not be blank")
                t.length > 2000 -> ValidationResult.Invalid("text too long (max 2000 chars)")
                else -> ValidationResult.Valid
            }
        }
    }

    install(StatusPages) {
        exception<RequestValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                com.example.adapters.http.errors.ApiErrorEnvelope(
                    com.example.adapters.http.errors.ApiError(
                        code = com.example.adapters.http.errors.ApiErrorCodes.VALIDATION_ERROR,
                        message = "Validation failed",
                        details = mapOf("reasons" to cause.reasons.joinToString("; "))
                    )
                )
            )
        }

        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                com.example.adapters.http.errors.ApiErrorEnvelope(
                    com.example.adapters.http.errors.ApiError(
                        code = com.example.adapters.http.errors.ApiErrorCodes.BAD_REQUEST,
                        message = cause.message ?: "Bad request"
                    )
                )
            )
        }

        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                com.example.adapters.http.errors.ApiErrorEnvelope(
                    com.example.adapters.http.errors.ApiError(
                        code = com.example.adapters.http.errors.ApiErrorCodes.BAD_REQUEST,
                        message = cause.message ?: "Bad request"
                    )
                )
            )
        }

        exception<ContentTransformationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                com.example.adapters.http.errors.ApiErrorEnvelope(
                    com.example.adapters.http.errors.ApiError(
                        code = com.example.adapters.http.errors.ApiErrorCodes.BAD_REQUEST,
                        message = cause.message ?: "Bad request"
                    )
                )
            )
        }

        exception<io.ktor.client.plugins.HttpRequestTimeoutException> { call, cause ->
            log.warn("Upstream timeout. requestId=${call.callId}. ${cause.message}")
            call.respond(
                HttpStatusCode.GatewayTimeout,
                com.example.adapters.http.errors.ApiErrorEnvelope(
                    com.example.adapters.http.errors.ApiError(
                        code = com.example.adapters.http.errors.ApiErrorCodes.TIMEOUT,
                        message = "Upstream timeout"
                    )
                )
            )
        }

        exception<com.example.shared.UpstreamException> { call, cause ->
            val (status, code) = when {
                cause.status == HttpStatusCode.TooManyRequests ->
                    HttpStatusCode.TooManyRequests to com.example.adapters.http.errors.ApiErrorCodes.RATE_LIMIT

                cause.status == HttpStatusCode.Unauthorized || cause.status == HttpStatusCode.Forbidden ->
                    HttpStatusCode.BadGateway to com.example.adapters.http.errors.ApiErrorCodes.AUTH_ERROR

                cause.status.value in 500..599 ->
                    HttpStatusCode.BadGateway to com.example.adapters.http.errors.ApiErrorCodes.UPSTREAM_ERROR

                else ->
                    HttpStatusCode.BadGateway to com.example.adapters.http.errors.ApiErrorCodes.UPSTREAM_ERROR
            }

            log.warn(
                "UpstreamException. requestId=${call.callId} upstream=${cause.upstream} status=${cause.status.value} bodySnippet=${cause.bodySnippet.orEmpty()}"
            )

            call.respond(
                status,
                com.example.adapters.http.errors.ApiErrorEnvelope(
                    com.example.adapters.http.errors.ApiError(
                        code = code,
                        message = "Upstream error (${cause.upstream})",
                        details = buildMap {
                            put("upstreamStatus", cause.status.value.toString())
                            if (!cause.bodySnippet.isNullOrBlank()) put("body", cause.bodySnippet)
                        }
                    )
                )
            )
        }

        exception<Throwable> { call, cause ->
            log.error("Unhandled exception. requestId=${call.callId}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                com.example.adapters.http.errors.ApiErrorEnvelope(
                    com.example.adapters.http.errors.ApiError(
                        code = com.example.adapters.http.errors.ApiErrorCodes.INTERNAL_ERROR,
                        message = "Internal error"
                    )
                )
            )
        }
    }

    val createAssignmentUseCase = CreateAssignmentUseCase(
        assignments = dbPorts.assignments,
        teacherAuth = auth,
        rateLimiter = rate,
        joinKeyGen = JoinKeyGenerator.Default
    )

    val getAssignmentUseCase = GetAssignmentUseCase(dbPorts.assignments)

    val openSessionUseCase = OpenSessionUseCase(
        assignments = dbPorts.assignments,
        sessions = dbPorts.sessions,
        messages = dbPorts.messages,
        llm = llm,
        tx = tx,
        rateLimiter = rate
    )

    val postStudentMessageUseCase = PostStudentMessageUseCase(
        sessions = dbPorts.sessions,
        messages = dbPorts.messages,
        idem = dbPorts.idem,
        llm = llm,
        tx = tx,
        rateLimiter = rate,
        clock = clock
    )

    val getSessionUseCase = GetSessionUseCase(dbPorts.sessions)
    val listSessionsUseCase = ListSessionsUseCase(dbPorts.sessions)
    val deleteSessionUseCase = DeleteSessionUseCase(dbPorts.sessions)

    Routing.install(
        app = this,
        tx = tx,
        llm = llm,
        createAssignment = object : CreateAssignment {
            override fun execute(input: CreateAssignmentUseCase.Input): CreateAssignmentUseCase.Result {
                return createAssignmentUseCase.execute(input)
            }
        },
        getAssignment = object : GetAssignment {
            override fun execute(id: UUID): GetAssignmentUseCase.Result {
                return getAssignmentUseCase.execute(id)
            }
        },
        openSession = object : OpenSession {
            override suspend fun execute(input: OpenSessionUseCase.Input): OpenSessionUseCase.Result {
                return openSessionUseCase.execute(input)
            }
        },
        postStudentMessage = object : PostStudentMessage {
            override suspend fun execute(input: PostStudentMessageUseCase.Input): PostStudentMessageUseCase.Result {
                return postStudentMessageUseCase.execute(input)
            }
        },
        getSession = object : GetSession {
            override fun execute(id: UUID): GetSessionUseCase.Result {
                return getSessionUseCase.execute(id)
            }
        },
        listSessions = object : ListSessions {
            override fun execute(limit: Int, offset: Int): List<com.example.core.model.SessionSummary> {
                return listSessionsUseCase.execute(limit, offset)
            }
        },
        deleteSession = object : DeleteSession {
            override fun execute(id: UUID): Boolean {
                return deleteSessionUseCase.execute(id)
            }
        }
    )

    monitor.subscribe(ApplicationStopped) {
        cleanupJob?.cancel()
        clients.llmHttp.close()
        clients.gigaHttp?.close()
        runCatching { dataSource.close() }
    }
}
