package com.example

import kotlinx.serialization.Serializable

// ---------- Teacher side: assignments ----------

@Serializable
data class CreateAssignmentReq(
    val topic: String,
    val vocab: List<String> = emptyList(),
    val level: String? = null // "A2", "B1" и т.д.
)

@Serializable
data class CreateAssignmentResp(
    val assignmentId: String,
    val joinKey: String
)

@Serializable
data class AssignmentDto(
    val assignmentId: String,
    val joinKey: String,
    val topic: String,
    val vocab: List<String>,
    val level: String? = null
)

// ---------- Student side: open session by joinKey ----------

@Serializable
data class OpenSessionReq(
    val joinKey: String
)

@Serializable
data class OpenSessionResp(
    val sessionId: String,
    val assignmentId: String,
    val joinKey: String,
    val topic: String,
    val messages: List<MessageDto>
)

// ---------- Chat ----------

@Serializable
data class StudentMessageReq(
    val text: String
)

@Serializable
data class TutorMessageResp(
    val tutorText: String,
    val hint: String? = null,

    /**
     * Лексика, которую ученик уже использовал хотя бы раз в рамках этой сессии.
     */
    val vocabUsed: List<String> = emptyList(),

    /**
     * Лексика, которую ученик ещё ни разу не использовал в рамках этой сессии.
     */
    val vocabMissing: List<String> = emptyList()
)

// ---------- History & sessions listing ----------

@Serializable
data class HistoryResp(
    val sessionId: String,
    val assignmentId: String,
    val joinKey: String,
    val topic: String,
    val vocab: List<String>,
    val messages: List<MessageDto>
)

@Serializable
data class MessageDto(
    val role: String,
    val content: String
)

@Serializable
data class SessionSummaryDto(
    val sessionId: String,
    val assignmentId: String,
    val joinKey: String,
    val topic: String,
    val vocab: List<String>,
    val messageCount: Int
)

@Serializable
data class ListSessionsResp(
    val items: List<SessionSummaryDto>,
    val limit: Int,
    val offset: Int
)
