package com.example.adapters.http.util

import io.ktor.server.application.*
import io.ktor.server.plugins.origin

/**
 * Получение IP клиента с учётом прокси.
 *
 * Если сервис стоит за прокси (Render/Ingress), реальный IP часто приходит в X-Forwarded-For.
 * Иначе берём origin.remoteHost.
 */
fun ApplicationCall.clientIp(): String {
    val fwd = request.headers["X-Forwarded-For"]
        ?.split(",")
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    return fwd ?: request.origin.remoteHost
}
