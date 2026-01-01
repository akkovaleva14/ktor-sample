package com.example.core.model

import java.util.UUID

data class Session(
    val id: UUID,
    val assignmentId: UUID,
    val joinKey: String,
    val topic: String,
    val vocab: List<String>,
    val level: String? = null,
    val messages: List<Msg> = emptyList()
)
