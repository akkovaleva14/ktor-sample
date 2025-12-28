package com.example

import java.util.concurrent.ConcurrentHashMap

/**
 * Простейшая идемпотентность для POST /messages.
 *
 * Идея: клиент передаёт заголовок Idempotency-Key. Если из-за ретраев прилетает
 * тот же запрос повторно, сервер возвращает уже посчитанный ответ и не дублирует
 * сообщения в истории.
 *
 * Важно: это in-memory реализация, при рестарте кэш теряется.
 */
object IdempotencyStore {
    private data class Key(val sessionId: String, val idempotencyKey: String)
    private val map = ConcurrentHashMap<Key, TutorMessageResp>()

    fun get(sessionId: String, idempotencyKey: String): TutorMessageResp? =
        map[Key(sessionId, idempotencyKey)]

    fun put(sessionId: String, idempotencyKey: String, resp: TutorMessageResp) {
        map[Key(sessionId, idempotencyKey)] = resp
    }
}
