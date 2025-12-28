package com.example

import io.ktor.http.*
import io.ktor.server.application.*

/**
 * Простейшая авторизация для teacher-only эндпойнтов.
 *
 * Ожидает заголовок:
 * Authorization: Bearer <TEACHER_TOKEN>
 *
 * Важно: если TEACHER_TOKEN не задан, доступ закрыт (fail-closed).
 */
object TeacherAuth {

    private fun configuredToken(): String? =
        System.getenv("TEACHER_TOKEN")?.trim()?.ifBlank { null }

    fun isAllowed(call: ApplicationCall): Boolean {
        val token = configuredToken() ?: return false

        val auth = call.request.headers[HttpHeaders.Authorization].orEmpty().trim()
        if (!auth.startsWith("Bearer ", ignoreCase = true)) return false

        val presented = auth.removePrefix("Bearer").trim()
        return presented == token
    }
}
