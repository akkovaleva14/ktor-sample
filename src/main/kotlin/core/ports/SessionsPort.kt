package com.example.core.ports

import com.example.core.model.Session
import com.example.core.model.SessionSummary
import java.util.UUID

interface SessionsPort {
    fun createFromAssignment(
        assignmentId: UUID,
        joinKey: String,
        topic: String,
        vocab: List<String>,
        level: String?
    ): UUID

    /**
     * If [messageLimit] is provided, returns a snapshot with only the last N messages.
     * If null, returns full history.
     */
    fun getSessionSnapshot(sessionId: UUID, messageLimit: Int? = null): Session?

    fun delete(sessionId: UUID): Boolean
    fun listSummaries(limit: Int, offset: Int): List<SessionSummary>
}
