package com.example.core.model

import java.util.UUID

data class SessionSummary(
    val sessionId: UUID,
    val assignmentId: UUID,
    val joinKey: String,
    val topic: String,
    val vocab: List<String>,
    val messageCount: Int
)
