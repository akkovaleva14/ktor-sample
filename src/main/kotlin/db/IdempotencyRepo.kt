package com.example.db

import com.example.TutorMessageResp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.util.UUID

class IdempotencyRepo(private val db: Db) {
    private val json = Json { ignoreUnknownKeys = true }

    // Keep it minimal and stable. We'll detect pending by parsing JSON, not by string equality.
    private val pendingJson = """{"status":"pending"}"""

    fun get(sessionId: UUID, key: String): TutorMessageResp? = db.query { conn ->
        getInConn(conn, sessionId, key)
    }

    /**
     * Claim idempotency key. Returns true if this caller successfully claimed it.
     * (Implemented as "insert pending on conflict do nothing")
     */
    fun claim(sessionId: UUID, key: String): Boolean = db.query { conn ->
        claimInConn(conn, sessionId, key)
    }

    fun complete(sessionId: UUID, key: String, resp: TutorMessageResp) {
        db.query { conn ->
            completeInConn(conn, sessionId, key, resp)
        }
    }

    /**
     * In-transaction variants (use these inside db.tx { conn -> ... }).
     */
    fun getInTx(conn: Connection, sessionId: UUID, key: String): TutorMessageResp? =
        getInConn(conn, sessionId, key)

    fun claimInTx(conn: Connection, sessionId: UUID, key: String): Boolean =
        claimInConn(conn, sessionId, key)

    fun completeInTx(conn: Connection, sessionId: UUID, key: String, resp: TutorMessageResp) =
        completeInConn(conn, sessionId, key, resp)

    // ---- internals

    private fun getInConn(conn: Connection, sessionId: UUID, key: String): TutorMessageResp? {
        val raw = conn.prepared(
            "select response::text from public.idempotency where session_id = ? and idem_key = ?",
            sessionId, key
        ).queryOneOrNull { rs -> rs.getString(1) } ?: return null

        if (isPendingJson(raw)) return null

        return runCatching {
            json.decodeFromString(TutorMessageResp.serializer(), raw)
        }.getOrNull()
    }

    private fun claimInConn(conn: Connection, sessionId: UUID, key: String): Boolean {
        val rows = conn.prepared(
            """
            insert into public.idempotency (session_id, idem_key, response)
            values (?, ?, (?::jsonb))
            on conflict do nothing
            """.trimIndent(),
            sessionId, key, pendingJson
        ).execUpdate()

        return rows == 1
    }

    private fun completeInConn(conn: Connection, sessionId: UUID, key: String, resp: TutorMessageResp) {
        val raw = json.encodeToString(TutorMessageResp.serializer(), resp)
        conn.prepared(
            "update public.idempotency set response = (?::jsonb) where session_id = ? and idem_key = ?",
            raw, sessionId, key
        ).execUpdate()
    }

    private fun isPendingJson(raw: String): Boolean {
        val el: JsonElement = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return false
        val obj = el as? JsonObject ?: return false

        val statusEl = obj["status"] ?: return false
        val status = runCatching { statusEl.jsonPrimitive.content }.getOrNull() ?: return false

        return status == "pending"
    }
}
