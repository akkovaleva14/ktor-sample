package com.example.core.model

import kotlinx.serialization.Serializable

/**
 * Canonical domain result for "tutor reply" (what we store in idempotency response JSON).
 *
 * Важно: HTTP DTO может отличаться (например, названия полей),
 * но доменная модель — стабильная.
 */
@Serializable
data class TutorReply(
    val tutorText: String,
    val hint: String? = null,
    val vocabUsed: List<String> = emptyList(),
    val vocabMissing: List<String> = emptyList()
)
