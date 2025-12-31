package com.example.db

import java.sql.Connection
import java.time.Instant
import java.util.UUID

data class DbMessage(
    val seq: Int,
    val role: String,
    val content: String,
    val createdAt: Instant
)

class MessagesRepo(private val db: Db) {

    fun listBySession(sessionId: UUID): List<DbMessage> = db.query { conn ->
        conn.prepared(
            """
            select seq, role, content, created_at
            from public.messages
            where session_id = ?
            order by seq asc
            """.trimIndent(),
            sessionId
        ).queryList { rs ->
            DbMessage(
                seq = rs.getInt("seq"),
                role = rs.getString("role"),
                content = rs.getString("content"),
                createdAt = rs.getTimestamp("created_at").toInstant()
            )
        }
    }

    /**
     * Returns last [limit] messages in correct chronological order (seq asc).
     * Useful for LLM context without loading full history.
     */
    fun listBySessionLast(sessionId: UUID, limit: Int): List<DbMessage> {
        require(limit > 0) { "limit must be > 0" }

        return db.query { conn ->
            val desc = conn.prepared(
                """
                select seq, role, content, created_at
                from public.messages
                where session_id = ?
                order by seq desc
                limit ?
                """.trimIndent(),
                sessionId, limit
            ).queryList { rs ->
                DbMessage(
                    seq = rs.getInt("seq"),
                    role = rs.getString("role"),
                    content = rs.getString("content"),
                    createdAt = rs.getTimestamp("created_at").toInstant()
                )
            }

            desc.asReversed()
        }
    }

    fun listStudentContents(sessionId: UUID): List<String> = db.query { conn ->
        conn.prepared(
            """
            select content
            from public.messages
            where session_id = ? and role = 'student'
            order by seq asc
            """.trimIndent(),
            sessionId
        ).queryList { rs -> rs.getString("content") }
    }

    fun countByRole(sessionId: UUID, role: String): Int = db.query { conn ->
        conn.prepared(
            """
            select count(*) 
            from public.messages 
            where session_id = ? and role = ?
            """.trimIndent(),
            sessionId, role
        ).queryOneOrNull { rs -> rs.getLong(1).toInt() } ?: 0
    }

    /**
     * Must be called inside a transaction.
     * Guarantees unique (session_id, seq) by locking the session row.
     *
     * Optimized: uses sessions.next_seq (O(1)) instead of max(seq).
     */
    fun appendInTx(conn: Connection, sessionId: UUID, role: String, content: String): Int {
        // Lock session row and read next_seq
        val nextSeq = conn.prepared(
            "select next_seq from public.sessions where id = ? for update",
            sessionId
        ).queryOneOrNull { rs -> rs.getInt(1) }
            ?: throw IllegalArgumentException("Session not found")

        conn.prepared(
            """
            insert into public.messages (session_id, seq, role, content)
            values (?, ?, ?, ?)
            """.trimIndent(),
            sessionId, nextSeq, role, content
        ).execUpdate()

        // Increment next_seq
        conn.prepared(
            "update public.sessions set next_seq = next_seq + 1 where id = ?",
            sessionId
        ).execUpdate()

        return nextSeq
    }
}
