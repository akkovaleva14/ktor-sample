package com.example.adapters.db.jdbc.repo

import com.example.adapters.db.jdbc.JdbcSession
import com.example.adapters.db.jdbc.execUpdate
import com.example.adapters.db.jdbc.mapping.vocabFromJson
import com.example.adapters.db.jdbc.mapping.vocabToJson
import com.example.adapters.db.jdbc.prepared
import com.example.adapters.db.jdbc.queryList
import com.example.adapters.db.jdbc.queryOneOrNull
import com.example.core.model.Msg
import com.example.core.model.Session
import com.example.core.model.SessionSummary
import com.example.core.ports.MessagesPort
import com.example.core.ports.SessionsPort
import java.util.UUID

class SessionsRepoJdbc(
    private val session: JdbcSession,
    private val messages: MessagesPort
) : SessionsPort {

    override fun createFromAssignment(
        assignmentId: UUID,
        joinKey: String,
        topic: String,
        vocab: List<String>,
        level: String?
    ): UUID {
        val sessionId = UUID.randomUUID()
        session.query { conn ->
            conn.prepared(
                """
                insert into public.sessions (id, assignment_id, join_key, topic, vocab, level)
                values (?, ?, ?, ?, (?::jsonb), ?)
                """.trimIndent(),
                sessionId, assignmentId, joinKey, topic, vocabToJson(vocab), level
            ).execUpdate()
        }
        return sessionId
    }

    override fun getSessionSnapshot(sessionId: UUID, messageLimit: Int?): Session? = session.query { conn ->
        val row = conn.prepared(
            """
            select id, assignment_id, join_key, topic, vocab::text as vocab_text, level
            from public.sessions
            where id = ?
            """.trimIndent(),
            sessionId
        ).queryOneOrNull { rs ->
            val id = rs.getObject("id", UUID::class.java)
            val assignmentId = rs.getObject("assignment_id", UUID::class.java)
            val joinKey = rs.getString("join_key")
            val topic = rs.getString("topic")
            val vocabJson = rs.getString("vocab_text")
            val level = rs.getString("level")
            arrayOf(id, assignmentId, joinKey, topic, vocabJson, level)
        } ?: return@query null

        val id = row[0] as UUID
        val assignmentId = row[1] as UUID
        val joinKey = row[2] as String
        val topic = row[3] as String
        val vocabJson = row[4] as String
        val level = row[5] as String?

        val dbMsgs = when (val lim = messageLimit) {
            null -> messages.listBySession(id)
            else -> messages.listBySessionLast(id, lim)
        }

        val msgs = dbMsgs.map { Msg(it.role, it.content) }

        Session(
            id = id,
            assignmentId = assignmentId,
            joinKey = joinKey,
            topic = topic,
            vocab = vocabFromJson(vocabJson),
            level = level,
            messages = msgs
        )
    }

    override fun delete(sessionId: UUID): Boolean = session.query { conn ->
        conn.prepared("delete from public.sessions where id = ?", sessionId).execUpdate() > 0
    }

    override fun listSummaries(limit: Int, offset: Int): List<SessionSummary> = session.query { conn ->
        conn.prepared(
            """
            select
              s.id,
              s.assignment_id,
              s.join_key,
              s.topic,
              s.vocab::text as vocab_text,
              coalesce(count(m.id), 0) as message_count
            from public.sessions s
            left join public.messages m on m.session_id = s.id
            group by s.id
            order by s.created_at desc
            limit ? offset ?
            """.trimIndent(),
            limit, offset
        ).queryList { rs ->
            SessionSummary(
                sessionId = rs.getObject("id", UUID::class.java),
                assignmentId = rs.getObject("assignment_id", UUID::class.java),
                joinKey = rs.getString("join_key"),
                topic = rs.getString("topic"),
                vocab = vocabFromJson(rs.getString("vocab_text")),
                messageCount = rs.getLong("message_count").toInt()
            )
        }
    }
}
