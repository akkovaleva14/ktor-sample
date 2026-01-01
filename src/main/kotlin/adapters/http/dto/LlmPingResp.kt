package com.example.adapters.http.dto

import kotlinx.serialization.Serializable

@Serializable
data class LlmPingResp(
    val ok: Boolean,
    val provider: String,
    val latencyMs: Long? = null,
    val details: String? = null,
    val reason: String? = null,
    val httpStatus: Int? = null
)
