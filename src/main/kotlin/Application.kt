package com.example

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.io.path.createTempFile
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"

    embeddedServer(Netty, port = port, host = host) {
        module()
    }.start(wait = true)
}

/**
 * Главный модуль Ktor.
 *
 * В проде можно вызывать module() без параметров — LLM-провайдер выбирается по env.
 * В тестах можно передать llmOverride (fake), чтобы тесты были детерминированными.
 */
fun Application.module(llmOverride: LlmClient? = null) {
    val log = this.log

    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
        replyToHeader(HttpHeaders.XRequestId)
    }

    install(CallLogging) {
        mdc("requestId") { call -> call.callId }
    }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    install(StatusPages) {
        exception<RequestValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorEnvelope(
                    ApiError(
                        code = ApiErrorCodes.VALIDATION_ERROR,
                        message = "Validation failed",
                        details = mapOf("reasons" to cause.reasons.joinToString("; "))
                    )
                )
            )
        }

        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorEnvelope(
                    ApiError(
                        code = ApiErrorCodes.BAD_REQUEST,
                        message = cause.message ?: "Bad request"
                    )
                )
            )
        }

        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorEnvelope(
                    ApiError(
                        code = ApiErrorCodes.BAD_REQUEST,
                        message = cause.message ?: "Bad request"
                    )
                )
            )
        }

        exception<ContentTransformationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorEnvelope(
                    ApiError(
                        code = ApiErrorCodes.BAD_REQUEST,
                        message = cause.message ?: "Bad request"
                    )
                )
            )
        }

        // ⏱ Таймауты на исходящих HTTP-запросах (к LLM/OAuth)
        exception<HttpRequestTimeoutException> { call, cause ->
            log.warn("Upstream timeout. requestId=${call.callId}. ${cause.message}")
            call.respond(
                HttpStatusCode.GatewayTimeout,
                ApiErrorEnvelope(
                    ApiError(
                        code = ApiErrorCodes.TIMEOUT,
                        message = "Upstream timeout"
                    )
                )
            )
        }

        // 🌐 Типизированные ошибки внешних API
        exception<UpstreamException> { call, cause ->
            val (status, code) = when {
                cause.status == HttpStatusCode.TooManyRequests ->
                    HttpStatusCode.TooManyRequests to ApiErrorCodes.RATE_LIMIT

                cause.status == HttpStatusCode.Unauthorized || cause.status == HttpStatusCode.Forbidden ->
                    HttpStatusCode.BadGateway to ApiErrorCodes.AUTH_ERROR

                cause.status.value in 500..599 ->
                    HttpStatusCode.BadGateway to ApiErrorCodes.UPSTREAM_ERROR

                else ->
                    HttpStatusCode.BadGateway to ApiErrorCodes.UPSTREAM_ERROR
            }

            // Безопасно: не логируем секреты, только статус/сниппет
            log.warn(
                "UpstreamException. requestId=${call.callId} upstream=${cause.upstream} status=${cause.status.value} bodySnippet=${cause.bodySnippet.orEmpty()}"
            )

            call.respond(
                status,
                ApiErrorEnvelope(
                    ApiError(
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
                ApiErrorEnvelope(
                    ApiError(
                        code = ApiErrorCodes.INTERNAL_ERROR,
                        message = "Internal error"
                    )
                )
            )
        }
    }

    install(RequestValidation) {
        validate<CreateAssignmentReq> { req ->
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

        validate<OpenSessionReq> { req ->
            val key = req.joinKey.trim()
            when {
                key.isEmpty() -> ValidationResult.Invalid("joinKey must not be blank")
                key.length !in 4..12 -> ValidationResult.Invalid("joinKey length must be 4..12")
                else -> ValidationResult.Valid
            }
        }

        validate<StudentMessageReq> { req ->
            val t = req.text.trim()
            when {
                t.isEmpty() -> ValidationResult.Invalid("text must not be blank")
                t.length > 2000 -> ValidationResult.Invalid("text too long (max 2000 chars)")
                else -> ValidationResult.Valid
            }
        }
    }

    val clientJson = Json { ignoreUnknownKeys = true }

    /**
     * Общие настройки исходящих HTTP-клиентов:
     * - JSON
     * - таймауты
     * - ретраи только для временных сбоев (5xx/429/таймауты)
     */
    fun HttpClientConfig<CIOEngineConfig>.installCommonClientHardening(name: String) {
        install(ClientContentNegotiation) { json(clientJson) }

        install(HttpTimeout) {
            connectTimeoutMillis = 5.seconds.inWholeMilliseconds
            socketTimeoutMillis = 20.seconds.inWholeMilliseconds
            requestTimeoutMillis = 25.seconds.inWholeMilliseconds
        }

        install(HttpRequestRetry) {
            maxRetries = 2
            retryIf { _, response ->
                response.status == HttpStatusCode.TooManyRequests ||
                        response.status == HttpStatusCode.BadGateway ||
                        response.status == HttpStatusCode.ServiceUnavailable ||
                        response.status == HttpStatusCode.GatewayTimeout ||
                        response.status.value in 500..599
            }
            retryOnExceptionIf { _, cause ->
                cause is HttpRequestTimeoutException ||
                        cause is java.net.SocketTimeoutException ||
                        cause is java.io.IOException
            }
            delayMillis { retry -> (200L * (retry + 1)).coerceAtMost(800L) }
            modifyRequest { request ->
                request.headers.append("X-Retry-Attempt", retryCount.toString())
                request.headers.append("X-Client-Name", name)
            }
        }

        expectSuccess = false
    }

    val llmHttp = HttpClient(CIO) { installCommonClientHardening(name = "llm-default") }

    val provider = System.getenv("LLM_PROVIDER")?.lowercase() ?: "ollama"
    log.info("LLM_PROVIDER=$provider")

    val gigaHttp: HttpClient? = run {
        if (provider != "gigachat") {
            log.info("GigaChat client disabled (provider=$provider)")
            return@run null
        }

        val caPemEnv = System.getenv("GIGACHAT_CA_PEM")?.trim().orEmpty()
        val caPemB64 = System.getenv("GIGACHAT_CA_PEM_B64")?.trim().orEmpty()
        val caPemPath = System.getenv("GIGACHAT_CA_PEM_PATH")?.trim().orEmpty()

        val pemText: String = when {
            caPemB64.isNotEmpty() -> {
                val decoded = try {
                    String(Base64.getDecoder().decode(caPemB64))
                } catch (e: Throwable) {
                    log.error("GIGACHAT_CA_PEM_B64 is set but cannot be base64-decoded", e)
                    ""
                }
                decoded.trim()
            }
            caPemEnv.isNotEmpty() -> caPemEnv
            else -> ""
        }

        val pemFile: File? = when {
            pemText.contains("BEGIN CERTIFICATE") -> {
                val tmp = createTempFile(prefix = "gigachat-ca-", suffix = ".pem").toFile()
                tmp.writeText(pemText + if (pemText.endsWith("\n")) "" else "\n")
                log.info("GigaChat CA loaded from ENV (exists=${tmp.exists()}, size=${tmp.length()})")
                tmp
            }

            caPemPath.isNotEmpty() -> {
                val f = File(caPemPath)
                log.info("GigaChat CA path provided (path=${f.path}, exists=${f.exists()}, size=${if (f.exists()) f.length() else 0})")
                if (f.exists()) f else null
            }

            else -> {
                log.info("GigaChat CA not provided. Using system trust store.")
                null
            }
        }

        val tm = if (pemFile != null && pemFile.exists()) {
            try {
                TlsPemTrust.trustManagerFromPemFile(pemFile.path)
            } catch (e: Throwable) {
                log.error("Failed to load PEM trust manager from file: ${pemFile.path}. Falling back to system trust store.", e)
                null
            }
        } else null

        if (tm != null) {
            log.info("GigaChat HttpClient(CIO) with custom trustManager created")
            HttpClient(CIO) {
                installCommonClientHardening(name = "gigachat")
                engine { https { trustManager = tm } }
            }
        } else {
            log.info("GigaChat HttpClient(CIO) with system trust store created")
            HttpClient(CIO) { installCommonClientHardening(name = "gigachat") }
        }
    }

    val llm: LlmClient = llmOverride ?: run {
        log.info("Selecting LLM implementation for provider=$provider (override=${llmOverride != null})")

        when (provider) {
            "gigachat" -> {
                val basic = System.getenv("GIGACHAT_AUTH_BASIC")
                    ?: error("GIGACHAT_AUTH_BASIC is required (value like: Basic <base64>)")
                val scope = System.getenv("GIGACHAT_SCOPE") ?: "GIGACHAT_API_PERS"
                val model = System.getenv("GIGACHAT_MODEL") ?: "GigaChat-2-Pro"

                val http = gigaHttp ?: error("gigaHttp is null: failed to create GigaChat HttpClient")
                val auth = GigaChatAuth(http, basicAuthHeaderValue = basic, scope = scope)

                GigaChatClient(http = http, auth = auth, model = model)
            }

            else -> OllamaClient(
                http = llmHttp,
                baseUrl = "http://localhost:11434",
                model = "qwen2.5:7b"
            )
        }
    }

    monitor.subscribe(ApplicationStopped) {
        llmHttp.close()
        gigaHttp?.close()
    }

    configureRouting(llm)
}
