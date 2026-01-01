package com.example.core.usecase

import com.example.core.model.Session
import com.example.core.ports.SessionsPort
import java.util.UUID

class GetSessionUseCase(
    private val sessions: SessionsPort
) {
    sealed class Result {
        data class Found(val session: Session) : Result()
        data object NotFound : Result()
    }

    fun execute(sessionId: UUID): Result {
        val s = sessions.getSessionSnapshot(sessionId, messageLimit = null) ?: return Result.NotFound
        return Result.Found(s)
    }
}
