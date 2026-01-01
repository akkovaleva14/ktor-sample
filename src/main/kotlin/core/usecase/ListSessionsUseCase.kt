package com.example.core.usecase

import com.example.core.model.SessionSummary
import com.example.core.ports.SessionsPort

class ListSessionsUseCase(
    private val sessions: SessionsPort
) {
    fun execute(limit: Int, offset: Int): List<SessionSummary> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        require(offset >= 0) { "offset must be >= 0" }
        return sessions.listSummaries(limit, offset)
    }
}
