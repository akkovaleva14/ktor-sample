package com.example.db

import com.example.TutorMessageResp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID

class IdempotencyRepo(private val db: Db) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Stored as response JSON.
     * We'll detect pending by parsing JSON, not by string equality.
     */
    private val pendingJson = """{"status":"pending"}"""

    /**
     * If a request claimed an idempotency key but never completed (crash/timeout),
     * we should allow retries after some time.
     */
    private val pendingTtl: Duration = Duration.ofMinutes(2)

    fun get(sessionId: UUID, key: String): TutorMessageResp? = db.query { conn ->
        getInConn(conn, sessionId, key)
    }

    fun claim(sessionId: UUID, key: String): Boolean = db.query { conn ->
        claimInConn(conn, sessionId, key)
    }

    fun complete(sessionId: UUID, key: String, resp: TutorMessageResp) {
        db.query { conn ->
            completeInConn(conn, sessionId, key, resp)
        }
    }

    /**
     * Deletes idempotency rows older than [ttl].
     * Returns number of deleted rows.
     */
    fun cleanupOlderThan(ttl: Duration): Int {
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be > 0" }
        return db.query { conn ->
            conn.prepared(
                "delete from public.idempotency where created_at < (now() - (?::interval))",
                durationToPgInterval(ttl)
            ).execUpdate()
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

    private data class RawRow(val responseText: String, val createdAt: Instant)

    private fun getInConn(conn: Connection, sessionId: UUID, key: String): TutorMessageResp? {
        val row = conn.prepared(
            """
            select response::text as response_text, created_at
            from public.idempotency
            where session_id = ? and idem_key = ?
            """.trimIndent(),
            sessionId, key
        ).queryOneOrNull { rs ->
            RawRow(
                responseText = rs.getString("response_text"),
                createdAt = rs.getTimestamp("created_at").toInstant()
            )
        } ?: return null

        if (isPendingJson(row.responseText)) {
            val age = Duration.between(row.createdAt, Instant.now())
            if (age > pendingTtl) return null
            return null
        }

        return runCatching {
            json.decodeFromString(TutorMessageResp.serializer(), row.responseText)
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

    private fun durationToPgInterval(d: Duration): String {
        val totalSeconds = d.seconds
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60

        // Safe textual interval: "X days Y hours Z minutes S seconds"
        return buildString {
            if (days != 0L) append("$days days ")
            if (hours != 0L) append("$hours hours ")
            if (minutes != 0L) append("$minutes minutes ")
            if (seconds != 0L || isEmpty()) append("$seconds seconds")
        }.trim()
    }
}
