package com.example.core.usecase

import com.example.core.ports.SessionsPort
import java.util.UUID

class DeleteSessionUseCase(
    private val sessions: SessionsPort
) {
    fun execute(sessionId: UUID): Boolean = sessions.delete(sessionId)
}
