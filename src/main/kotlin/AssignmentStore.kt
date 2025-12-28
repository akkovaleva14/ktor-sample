package com.example

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Assignment(
    val id: String,
    val joinKey: String,
    val topic: String,
    val vocab: List<String>,
    val level: String? = null
)

/**
 * In-memory хранилище заданий.
 *
 * Важно: данные не переживают рестарт сервиса.
 */
object AssignmentStore {
    private val byId = ConcurrentHashMap<String, Assignment>()
    private val byJoinKey = ConcurrentHashMap<String, Assignment>()

    private val rng = SecureRandom()

    // Читаемый Base32 без неоднозначных символов (нет I, L, O, U)
    private val alphabet = "ABCDEFGHJKMNPQRSTVWXYZ23456789"

    fun create(topic: String, vocab: List<String>, level: String? = null): Assignment {
        val id = UUID.randomUUID().toString()
        val joinKey = generateUniqueJoinKey(length = 6)

        val assignment = Assignment(
            id = id,
            joinKey = joinKey,
            topic = topic,
            vocab = vocab,
            level = level
        )

        byId[id] = assignment
        byJoinKey[joinKey] = assignment
        return assignment
    }

    fun getById(id: String): Assignment? = byId[id]

    fun getByJoinKey(joinKey: String): Assignment? = byJoinKey[joinKey]

    private fun generateUniqueJoinKey(length: Int): String {
        repeat(50) {
            val key = buildString(length) {
                repeat(length) {
                    append(alphabet[rng.nextInt(alphabet.length)])
                }
            }
            if (!byJoinKey.containsKey(key)) return key
        }
        return UUID.randomUUID().toString().replace("-", "").take(length).uppercase()
    }
}
