package com.example

import io.ktor.http.*

/**
 * Единый тип исключений для ошибок внешних API (LLM/OAuth).
 * Позволяет в StatusPages маппить в нормальные коды ошибок.
 */
class UpstreamException(
    val upstream: String,
    val status: HttpStatusCode,
    val bodySnippet: String? = null,
    message: String = "Upstream error: $upstream HTTP ${status.value}"
) : RuntimeException(message)
