package com.example.core.ports

/**
 * Teacher-only authorization port.
 *
 * Смысл порта: core/usecase не должен знать ни про Ktor, ни про заголовки HTTP —
 * он работает с уже извлечённым "presented bearer token" (или отсутствием).
 */
interface TeacherAuthPort {
    fun isAllowed(presentedBearerToken: String?): Boolean
}
