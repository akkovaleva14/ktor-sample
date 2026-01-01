package com.example.core.ports

import com.example.core.model.TutorReply
import java.time.Duration
import java.util.UUID

interface IdempotencyPort {
    fun get(sessionId: UUID, key: String): TutorReply?

    /**
     * Claim the idempotency key (pending).
     *
     * Must be called inside a transaction if you want "claim + insert student message" atomicity.
     */
    fun claimInTx(sessionId: UUID, key: String): Boolean

    /**
     * Store final response.
     *
     * Must be called inside a transaction if you want "insert tutor + complete idempotency" atomicity.
     */
    fun completeInTx(sessionId: UUID, key: String, resp: TutorReply)

    /**
     * Deletes idempotency rows older than [ttl].
     * Returns number of deleted rows.
     */
    fun cleanupOlderThan(ttl: Duration): Int
}
