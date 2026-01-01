package com.example.app.wiring

import com.example.adapters.auth.TeacherAuthEnv
import com.example.adapters.db.jdbc.JdbcSession
import com.example.adapters.db.jdbc.JdbcTx
import com.example.adapters.db.jdbc.repo.AssignmentsRepoJdbc
import com.example.adapters.db.jdbc.repo.IdempotencyRepoJdbc
import com.example.adapters.db.jdbc.repo.MessagesRepoJdbc
import com.example.adapters.db.jdbc.repo.SessionsRepoJdbc
import com.example.adapters.llm.gigachat.GigaChatAuth
import com.example.adapters.llm.gigachat.GigaChatClient
import com.example.adapters.llm.ollama.OllamaClient
import com.example.adapters.rate.InMemoryRateLimiter
import com.example.adapters.db.tls.TlsPemTrust
import com.example.core.ports.*
import com.example.shared.UpstreamException
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempFile
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

/**
 * Единственное место, где "склеиваем" реализации портов (adapters) с use case-ами (core).
 *
 * В Clean Architecture это часто называют composition root / wiring.
 * Идея: менять провайдер LLM или storage — правим только тут.
 */
class AppWiring(
    private val log: org.slf4j.Logger
) {
    data class Clients(
        val llmHttp: HttpClient,
        val gigaHttp: HttpClient?
    )

    private val clientJson = Json { ignoreUnknownKeys = true }

    private fun HttpClientConfig<CIOEngineConfig>.installCommonClientHardening(name: String) {
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

        // Важно: сами проверяем коды (чтобы бросать UpstreamException с bodySnippet).
        expectSuccess = false
    }

    fun createHttpClients(): Clients {
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
                    log.info(
                        "GigaChat CA path provided (path=${f.path}, exists=${f.exists()}, size=${
                            if (f.exists()) f.length() else 0
                        })"
                    )
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
                    log.error(
                        "Failed to load PEM trust manager from file: ${pemFile.path}. Falling back to system trust store.",
                        e
                    )
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

        return Clients(llmHttp = llmHttp, gigaHttp = gigaHttp)
    }

    fun createTeacherAuth(): TeacherAuthPort = TeacherAuthEnv

    fun createRateLimiter(): RateLimiterPort = InMemoryRateLimiter

    fun createClock(): ClockPort = ClockPort.System

    fun createTx(ds: javax.sql.DataSource): Pair<JdbcSession, TxPort> {
        val session = JdbcSession(ds)
        return session to JdbcTx(session)
    }

    fun createDbPorts(session: JdbcSession): DbPorts {
        val messages: MessagesPort = MessagesRepoJdbc(session)
        val sessions: SessionsPort = SessionsRepoJdbc(session, messages)
        val assignments: AssignmentsPort = AssignmentsRepoJdbc(session)
        val idem: IdempotencyPort = IdempotencyRepoJdbc(session)

        return DbPorts(assignments, sessions, messages, idem)
    }

    data class DbPorts(
        val assignments: AssignmentsPort,
        val sessions: SessionsPort,
        val messages: MessagesPort,
        val idem: IdempotencyPort
    )

    fun createLlm(
        clients: Clients,
        llmOverride: LlmPort?
    ): LlmPort {
        if (llmOverride != null) return llmOverride

        val provider = System.getenv("LLM_PROVIDER")?.lowercase() ?: "ollama"
        log.info("Selecting LLM implementation for provider=$provider (override=false)")

        return when (provider) {
            "gigachat" -> {
                val basic = System.getenv("GIGACHAT_AUTH_BASIC")
                    ?: error("GIGACHAT_AUTH_BASIC is required (value like: Basic <base64>)")
                val scope = System.getenv("GIGACHAT_SCOPE") ?: "GIGACHAT_API_PERS"
                val model = System.getenv("GIGACHAT_MODEL") ?: "GigaChat-2-Pro"

                val http = clients.gigaHttp ?: error("gigaHttp is null: failed to create GigaChat HttpClient")
                val auth = GigaChatAuth(http, basicAuthHeaderValue = basic, scope = scope)

                GigaChatClient(http = http, auth = auth, model = model)
            }

            else -> {
                // Ollama default (local)
                OllamaClient(
                    http = clients.llmHttp,
                    baseUrl = System.getenv("OLLAMA_URL") ?: "http://localhost:11434",
                    model = System.getenv("OLLAMA_MODEL") ?: "qwen2.5:7b"
                )
            }
        }
    }
}
