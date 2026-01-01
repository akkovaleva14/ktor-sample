package com.example.adapters.db.jdbc.repo

import com.example.adapters.db.jdbc.JdbcSession
import com.example.adapters.db.jdbc.execUpdate
import com.example.adapters.db.jdbc.prepared
import com.example.adapters.db.jdbc.queryOneOrNull
import com.example.core.model.TutorReply
import com.example.core.ports.IdempotencyPort
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.UUID

class IdempotencyRepoJdbc(private val session: JdbcSession) : IdempotencyPort {
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

    override fun get(sessionId: UUID, key: String): TutorReply? = session.query { conn ->
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
        } ?: return@query null

        // Pending means: someone claimed the key but didn't complete yet.
        // We return null so the caller can decide how to respond (e.g. 409 and ask client to retry).
        if (isPendingJson(row.responseText)) {
            val age = Duration.between(row.createdAt, Instant.now())

            // If it's too old, treat it as stale and allow retry (i.e., behave like "not found").
            if (age > pendingTtl) return@query null

            // Still pending and fresh → also "not found" here (use case will translate to conflict/in-progress).
            return@query null
        }

        return@query runCatching {
            json.decodeFromString(TutorReply.serializer(), row.responseText)
        }.getOrNull()
    }

    override fun claimInTx(sessionId: UUID, key: String): Boolean {
        // Must run in tx for atomic "claim + insert student" in use case.
        val rows = session.query { conn ->
            conn.prepared(
                """
                insert into public.idempotency (session_id, idem_key, response)
                values (?, ?, (?::jsonb))
                on conflict do nothing
                """.trimIndent(),
                sessionId, key, pendingJson
            ).execUpdate()
        }
        return rows == 1
    }

    override fun completeInTx(sessionId: UUID, key: String, resp: TutorReply) {
        val raw = json.encodeToString(TutorReply.serializer(), resp)
        session.query { conn ->
            conn.prepared(
                "update public.idempotency set response = (?::jsonb) where session_id = ? and idem_key = ?",
                raw, sessionId, key
            ).execUpdate()
        }
    }

    override fun cleanupOlderThan(ttl: Duration): Int {
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be > 0" }
        return session.query { conn ->
            conn.prepared(
                "delete from public.idempotency where created_at < (now() - (?::interval))",
                durationToPgInterval(ttl)
            ).execUpdate()
        }
    }

    // ---- internals

    private data class RawRow(val responseText: String, val createdAt: Instant)

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
