package com.example.app

import com.example.adapters.db.jdbc.JdbcSession
import com.example.adapters.db.jdbc.prepared
import com.example.adapters.db.jdbc.queryOneOrNull
import com.example.core.ports.IdempotencyPort
import com.example.core.ports.SessionsPort
import java.time.Duration

class MaintenanceRunner(
    private val jdbc: JdbcSession,
    private val idempotency: IdempotencyPort,
    private val sessions: SessionsPort
) {
    private val lockKey: Long = 4242424242L

    data class Report(
        val skipped: Boolean,
        val idempotencyDeleted: Int = 0,
        val sessionsDeleted: Int = 0
    )

    fun run(idemTtlDays: Long = 7, sessionsTtlDays: Long = 30): Report {
        val locked = jdbc.query { conn ->
            conn.prepared("select pg_try_advisory_lock(?)", lockKey)
                .queryOneOrNull { rs -> rs.getBoolean(1) } ?: false
        }
        if (!locked) return Report(skipped = true)

        return try {
            val idem = idempotency.cleanupOlderThan(Duration.ofDays(idemTtlDays))
            val sess = sessions.cleanupOlderThan(Duration.ofDays(sessionsTtlDays))
            Report(skipped = false, idempotencyDeleted = idem, sessionsDeleted = sess)
        } finally {
            jdbc.query { conn ->
                conn.prepared("select pg_advisory_unlock(?)", lockKey)
                    .queryOneOrNull { /* ignore */ }
            }
        }
    }
}
