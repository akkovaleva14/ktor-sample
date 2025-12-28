package com.example

import io.ktor.http.*

/**
 * Единый тип исключений для ошибок внешних API (LLM/OAuth).
 * Нужен, чтобы StatusPages мог стабильно маппить их в понятные коды ошибок API.
 */
class UpstreamException(
    val upstream: String,
    val status: HttpStatusCode,
    val bodySnippet: String? = null,
    message: String = "Upstream error: $upstream HTTP ${status.value}"
) : RuntimeException(message)
