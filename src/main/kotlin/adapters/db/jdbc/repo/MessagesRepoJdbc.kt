package com.example.adapters.db.jdbc.repo

import com.example.adapters.db.jdbc.JdbcSession
import com.example.adapters.db.jdbc.execUpdate
import com.example.adapters.db.jdbc.prepared
import com.example.adapters.db.jdbc.queryList
import com.example.adapters.db.jdbc.queryOneOrNull
import com.example.core.ports.DbMessage
import com.example.core.ports.MessagesPort
import java.util.UUID

class MessagesRepoJdbc(private val session: JdbcSession) : MessagesPort {

    override fun listBySession(sessionId: UUID): List<DbMessage> = session.query { conn ->
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

    override fun listBySessionLast(sessionId: UUID, limit: Int): List<DbMessage> {
        require(limit > 0) { "limit must be > 0" }

        return session.query { conn ->
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

    override fun listStudentContents(sessionId: UUID): List<String> = session.query { conn ->
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

    override fun listStudentContentsLast(sessionId: UUID, limit: Int): List<String> {
        require(limit > 0) { "limit must be > 0" }

        return session.query { conn ->
            val desc = conn.prepared(
                """
                select content
                from public.messages
                where session_id = ? and role = 'student'
                order by seq desc
                limit ?
                """.trimIndent(),
                sessionId, limit
            ).queryList { rs -> rs.getString("content") }

            desc.asReversed()
        }
    }

    override fun countByRole(sessionId: UUID, role: String): Int = session.query { conn ->
        conn.prepared(
            """
            select count(*)
            from public.messages
            where session_id = ? and role = ?
            """.trimIndent(),
            sessionId, role
        ).queryOneOrNull { rs -> rs.getLong(1).toInt() } ?: 0
    }

    override fun appendInTx(sessionId: UUID, role: String, content: String): Int {
        // Must be called within tx: JdbcSession will reuse the ambient Connection.
        return session.query { conn ->
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

            conn.prepared(
                "update public.sessions set next_seq = next_seq + 1 where id = ?",
                sessionId
            ).execUpdate()

            nextSeq
        }
    }
}
