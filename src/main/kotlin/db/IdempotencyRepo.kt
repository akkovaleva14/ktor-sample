package com.example.db

import com.example.TutorMessageResp
import kotlinx.serialization.json.Json
import java.util.UUID

class IdempotencyRepo(private val db: Db) {
    private val json = Json { ignoreUnknownKeys = true }

    private val pendingJson = """{"status":"pending"}"""

    fun get(sessionId: UUID, key: String): TutorMessageResp? = db.query { conn ->
        val raw = conn.prepared(
            "select response::text from public.idempotency where session_id = ? and idem_key = ?",
            sessionId, key
        ).queryOneOrNull { rs -> rs.getString(1) } ?: return@query null

        if (raw == pendingJson) return@query null
        runCatching { json.decodeFromString(TutorMessageResp.serializer(), raw) }.getOrNull()
    }

    fun claim(sessionId: UUID, key: String): Boolean = db.query { conn ->
        val rows = conn.prepared(
            """
            insert into public.idempotency (session_id, idem_key, response)
            values (?, ?, (?::jsonb))
            on conflict do nothing
            """.trimIndent(),
            sessionId, key, pendingJson
        ).execUpdate()
        rows == 1
    }

    fun complete(sessionId: UUID, key: String, resp: TutorMessageResp) {
        val raw = json.encodeToString(TutorMessageResp.serializer(), resp)
        db.query { conn ->
            conn.prepared(
                "update public.idempotency set response = (?::jsonb) where session_id = ? and idem_key = ?",
                raw, sessionId, key
            ).execUpdate()
        }
    }
}
