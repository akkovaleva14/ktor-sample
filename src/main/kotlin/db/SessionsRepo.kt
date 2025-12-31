package com.example.db

import com.example.Msg
import com.example.Session
import com.example.SessionSummaryDto
import java.util.UUID

class SessionsRepo(private val db: Db, private val messages: MessagesRepo) {

    fun createFromAssignment(
        assignmentId: UUID,
        joinKey: String,
        topic: String,
        vocab: List<String>,
        level: String?
    ): UUID {
        val sessionId = UUID.randomUUID()
        db.query { conn ->
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

    fun getSessionSnapshot(sessionId: UUID): Session? = db.query { conn ->
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

        val msgs = messages.listBySession(id)
            .map { Msg(it.role, it.content) }
            .toMutableList()

        Session(
            id = id.toString(),
            assignmentId = assignmentId.toString(),
            joinKey = joinKey,
            topic = topic,
            vocab = vocabFromJson(vocabJson),
            level = level,
            messages = msgs
        )
    }

    fun delete(sessionId: UUID): Boolean = db.query { conn ->
        conn.prepared("delete from public.sessions where id = ?", sessionId).execUpdate() > 0
    }

    fun listSummaries(limit: Int, offset: Int): List<SessionSummaryDto> = db.query { conn ->
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
            SessionSummaryDto(
                sessionId = rs.getObject("id").toString(),
                assignmentId = rs.getObject("assignment_id").toString(),
                joinKey = rs.getString("join_key"),
                topic = rs.getString("topic"),
                vocab = vocabFromJson(rs.getString("vocab_text")),
                messageCount = rs.getLong("message_count").toInt()
            )
        }
    }
}
