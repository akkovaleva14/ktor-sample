package com.example

/**
 * Абстракция над LLM-провайдерами (Ollama/GigaChat).
 *
 * Мы передаём в tutorReply:
 * - used/missing: покрытие лексики по сессии (а не по последнему сообщению),
 *   чтобы подсказки и "подталкивание" были логичными.
 */
interface LlmClient {

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
}
