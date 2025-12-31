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
     */
    fun appendInTx(conn: Connection, sessionId: UUID, role: String, content: String): Int {
        // lock session row
        conn.prepared(
            "select 1 from public.sessions where id = ? for update",
            sessionId
        ).use { ps ->
            ps.executeQuery().use { rs ->
                if (!rs.next()) throw IllegalArgumentException("Session not found")
            }
        }

        val nextSeq = conn.prepared(
            "select coalesce(max(seq), 0) + 1 from public.messages where session_id = ?",
            sessionId
        ).queryOneOrNull { rs -> rs.getInt(1) } ?: 1

        conn.prepared(
            """
            insert into public.messages (session_id, seq, role, content)
            values (?, ?, ?, ?)
            """.trimIndent(),
            sessionId, nextSeq, role, content
        ).execUpdate()

        return nextSeq
    }
}
