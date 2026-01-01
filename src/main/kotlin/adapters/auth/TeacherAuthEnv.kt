package com.example.adapters.auth

import com.example.core.ports.TeacherAuthPort

/**
 * Простейшая авторизация для teacher-only эндпойнтов.
 *
 * Ожидает заголовок:
 * Authorization: Bearer <TEACHER_TOKEN>
 *
 * Важно: если TEACHER_TOKEN не задан, доступ закрыт (fail-closed).
 *
 * В clean architecture это адаптер: читает env, проверяет токен.
 */
object TeacherAuthEnv : TeacherAuthPort {

    private fun configuredToken(): String? =
        System.getenv("TEACHER_TOKEN")?.trim()?.ifBlank { null }

    override fun isAllowed(presentedBearerToken: String?): Boolean {
        val token = configuredToken() ?: return false
        val presented = presentedBearerToken?.trim()?.ifBlank { null } ?: return false
        return presented == token
    }
}
