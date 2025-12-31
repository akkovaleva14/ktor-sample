package com.example.db

import com.example.Assignment
import java.util.UUID

class AssignmentsRepo(private val db: Db) {

    fun insert(a: Assignment) {
        db.query { conn ->
            conn.prepared(
                """
                insert into public.assignments (id, join_key, topic, vocab, level)
                values (?, ?, ?, (?::jsonb), ?)
                """.trimIndent(),
                UUID.fromString(a.id),
                a.joinKey,
                a.topic,
                vocabToJson(a.vocab),
                a.level
            ).execUpdate()
        }
    }

    fun getById(id: UUID): Assignment? = db.query { conn ->
        conn.prepared(
            """
            select id, join_key, topic, vocab::text as vocab_text, level
            from public.assignments
            where id = ?
            """.trimIndent(),
            id
        ).queryOneOrNull { rs ->
            Assignment(
                id = rs.getObject("id").toString(),
                joinKey = rs.getString("join_key"),
                topic = rs.getString("topic"),
                vocab = vocabFromJson(rs.getString("vocab_text")),
                level = rs.getString("level")
            )
        }
    }

    fun getByJoinKey(joinKey: String): Assignment? = db.query { conn ->
        conn.prepared(
            """
            select id, join_key, topic, vocab::text as vocab_text, level
            from public.assignments
            where join_key = ?
            """.trimIndent(),
            joinKey
        ).queryOneOrNull { rs ->
            Assignment(
                id = rs.getObject("id").toString(),
                joinKey = rs.getString("join_key"),
                topic = rs.getString("topic"),
                vocab = vocabFromJson(rs.getString("vocab_text")),
                level = rs.getString("level")
            )
        }
    }
}
