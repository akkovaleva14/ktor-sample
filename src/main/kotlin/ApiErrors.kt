package com.example

import kotlinx.serialization.Serializable

/**
 * Централизованный список кодов ошибок — чтобы не плодить "магические строки".
 */
object ApiErrorCodes {
    const val VALIDATION_ERROR = "validation_error"
    const val BAD_REQUEST = "bad_request"
    const val NOT_FOUND = "not_found"

    const val SESSION_NOT_FOUND = "session_not_found"
    const val ASSIGNMENT_NOT_FOUND = "assignment_not_found"
    const val INVALID_JOIN_KEY = "invalid_join_key"

    // Upstream / networking
    const val UPSTREAM_ERROR = "upstream_error"
    const val RATE_LIMIT = "rate_limit"
    const val AUTH_ERROR = "auth_error"
    const val TIMEOUT = "timeout"

    const val INTERNAL_ERROR = "internal_error"
}

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap()
)

@Serializable
data class ApiErrorEnvelope(
    val error: ApiError
)
