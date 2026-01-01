package com.example.core.model

import java.util.UUID

data class Assignment(
    val id: UUID,
    val joinKey: String,
    val topic: String,
    val vocab: List<String>,
    val level: String? = null
)
