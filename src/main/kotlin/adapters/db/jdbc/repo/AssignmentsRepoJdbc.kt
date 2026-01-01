package com.example.adapters.db.jdbc.repo

import com.example.adapters.db.jdbc.JdbcSession
import com.example.adapters.db.jdbc.execUpdate
import com.example.adapters.db.jdbc.mapping.vocabFromJson
import com.example.adapters.db.jdbc.mapping.vocabToJson
import com.example.adapters.db.jdbc.prepared
import com.example.adapters.db.jdbc.queryOneOrNull
import com.example.core.model.Assignment
import com.example.core.ports.AssignmentsPort
import java.util.UUID

class AssignmentsRepoJdbc(private val session: JdbcSession) : AssignmentsPort {

    override fun insert(a: Assignment) {
        session.query { conn ->
            conn.prepared(
                """
                insert into public.assignments (id, join_key, topic, vocab, level)
                values (?, ?, ?, (?::jsonb), ?)
                """.trimIndent(),
                a.id,
                a.joinKey,
                a.topic,
                vocabToJson(a.vocab),
                a.level
            ).execUpdate()
        }
    }

    override fun getById(id: UUID): Assignment? = session.query { conn ->
        conn.prepared(
            """
            select id, join_key, topic, vocab::text as vocab_text, level
            from public.assignments
            where id = ?
            """.trimIndent(),
            id
        ).queryOneOrNull { rs ->
            Assignment(
                id = rs.getObject("id", UUID::class.java),
                joinKey = rs.getString("join_key"),
                topic = rs.getString("topic"),
                vocab = vocabFromJson(rs.getString("vocab_text")),
                level = rs.getString("level")
            )
        }
    }

    override fun getByJoinKey(joinKey: String): Assignment? = session.query { conn ->
        conn.prepared(
            """
            select id, join_key, topic, vocab::text as vocab_text, level
            from public.assignments
            where join_key = ?
            """.trimIndent(),
            joinKey
        ).queryOneOrNull { rs ->
            Assignment(
                id = rs.getObject("id", UUID::class.java),
                joinKey = rs.getString("join_key"),
                topic = rs.getString("topic"),
                vocab = vocabFromJson(rs.getString("vocab_text")),
                level = rs.getString("level")
            )
        }
    }
}
