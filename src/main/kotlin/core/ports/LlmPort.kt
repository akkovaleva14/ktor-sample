package com.example.core.ports

import com.example.core.model.Session
import kotlin.time.Duration

/**
 * Абстракция над LLM-провайдерами (Ollama/GigaChat/…).
 *
 * Мы передаём в tutorReply:
 * - used/missing: покрытие лексики по сессии (а не по последнему сообщению),
 *   чтобы подсказки и "подталкивание" были логичными.
 */
interface LlmPort {

    /**
     * Генерирует стартовую реплику тьютора (мягкий вход в тему),
     * на основе темы и лексики преподавателя.
     */
    suspend fun generateOpener(
        topic: String,
        vocab: List<String>,
        level: String? = null
    ): String

    /**
     * Генерирует следующий ответ тьютора в рамках диалога.
     */
    suspend fun tutorReply(
        session: Session,
        studentText: String,
        used: List<String>,
        missing: List<String>
    ): String

    /**
     * Быстрая проверка доступности провайдера:
     * - сеть/эндпоинт
     * - авторизация (если есть)
     * - базовая готовность (например, модель доступна)
     */
    suspend fun ping(): LlmPingResult
}

sealed interface LlmPingResult {
    data class Ok(
        val provider: String,
        val latency: Duration? = null,
        val details: String? = null
    ) : LlmPingResult

    data class Fail(
        val provider: String,
        val reason: String,
        val httpStatus: Int? = null
    ) : LlmPingResult
}
