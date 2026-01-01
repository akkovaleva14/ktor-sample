package com.example.core.usecase

import com.example.core.model.Session
import com.example.core.ports.*

class OpenSessionUseCase(
    private val assignments: AssignmentsPort,
    private val sessions: SessionsPort,
    private val messages: MessagesPort,
    private val llm: LlmPort,
    private val tx: TxPort,
    private val rateLimiter: RateLimiterPort
) {
    data class Input(
        val ip: String,
        val joinKey: String
    )

    sealed class Result {
        data class Created(val session: Session) : Result()
        data object InvalidJoinKey : Result()
        data object RateLimited : Result()
    }

    suspend fun execute(input: Input): Result {
        val rlOk = rateLimiter.allow(
            key = "v1.sessions.open.ip=${input.ip}",
            policy = RateLimiterPort.Policy(limit = 30, windowMs = 60_000)
        )
        if (!rlOk) return Result.RateLimited

        val joinKey = input.joinKey.trim().uppercase()
        val a = assignments.getByJoinKey(joinKey) ?: return Result.InvalidJoinKey

        val sessionId = sessions.createFromAssignment(
            assignmentId = a.id,
            joinKey = a.joinKey,
            topic = a.topic,
            vocab = a.vocab,
            level = a.level
        )

        val opener = llm.generateOpener(
            topic = a.topic,
            vocab = a.vocab,
            level = a.level
        ).ifBlank {
            "Let’s start with something simple—what comes to mind first?"
        }

        // Persist opener inside tx (seq allocation via sessions.next_seq).
        tx.tx {
            messages.appendInTx(sessionId, role = "tutor", content = opener)
        }

        val snapshot = sessions.getSessionSnapshot(sessionId, messageLimit = null)
            ?: Session(
                id = sessionId,
                assignmentId = a.id,
                joinKey = a.joinKey,
                topic = a.topic,
                vocab = a.vocab,
                level = a.level,
                messages = listOf(com.example.core.model.Msg("tutor", opener))
            )

        return Result.Created(snapshot)
    }
}
