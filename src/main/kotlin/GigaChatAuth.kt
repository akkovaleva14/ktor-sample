package com.example

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

@Serializable
data class GigaChatTokenResp(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_at") val expiresAtMs: Long? = null,
    @SerialName("expires_in") val expiresInSec: Long? = null
)

/**
 * Получение и кеширование OAuth-токена GigaChat.
 *
 * Токен хранится в памяти и обновляется заранее (с запасом ~30 секунд).
 */
class GigaChatAuth(
    private val http: HttpClient,
    private val basicAuthHeaderValue: String, // "Basic xxx"
    private val scope: String
) {
    private data class Cached(val token: String, val expiresAtEpochMs: Long)
    private val cached = AtomicReference<Cached?>(null)

    suspend fun getValidToken(): String {
        val now = System.currentTimeMillis()
        val c = cached.get()
        if (c != null && now < c.expiresAtEpochMs - 30.seconds.inWholeMilliseconds) {
            return c.token
        }

        val rqUid = UUID.randomUUID().toString()

        val resp: HttpResponse = http.post("https://ngw.devices.sberbank.ru:9443/api/v2/oauth") {
            header("RqUID", rqUid)
            header(HttpHeaders.Authorization, basicAuthHeaderValue)
            accept(ContentType.Application.Json)
            setBody(
                FormDataContent(
                    Parameters.build { append("scope", scope) }
                )
            )
        }

        val bodyText = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            throw UpstreamException(
                upstream = "gigachat-oauth",
                status = resp.status,
                bodySnippet = bodyText.snip(800),
                message = "GigaChat OAuth failed: HTTP ${resp.status.value}"
            )
        }

        val tokenResp = Json { ignoreUnknownKeys = true }
            .decodeFromString(GigaChatTokenResp.serializer(), bodyText)

        val expiresAt = tokenResp.expiresAtMs
            ?: (now + ((tokenResp.expiresInSec ?: 1800) * 1000))

        cached.set(Cached(tokenResp.accessToken, expiresAt))
        return tokenResp.accessToken
    }
}
