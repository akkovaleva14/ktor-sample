package com.example.core.ports

import java.time.Instant
import java.util.UUID

data class DbMessage(
    val seq: Int,
    val role: String,
    val content: String,
    val createdAt: Instant
)

interface MessagesPort {
    fun listBySession(sessionId: UUID): List<DbMessage>

    /**
     * Returns last [limit] messages in correct chronological order (seq asc).
     * Useful for LLM context without loading full history.
     */
    fun listBySessionLast(sessionId: UUID, limit: Int): List<DbMessage>

    fun listStudentContents(sessionId: UUID): List<String>

    /**
     * Returns last [limit] student message contents in chronological order.
     * Avoids scanning an ever-growing session history on each request.
     */
    fun listStudentContentsLast(sessionId: UUID, limit: Int): List<String>

    fun countByRole(sessionId: UUID, role: String): Int

    /**
     * Append message using sessions.next_seq allocation under tx.
     *
     * Must be called inside a transaction.
     */
    fun appendInTx(sessionId: UUID, role: String, content: String): Int
}
