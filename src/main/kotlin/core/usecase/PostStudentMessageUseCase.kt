package com.example.core.usecase

import com.example.core.model.TutorReply
import com.example.core.policy.HintPicker
import com.example.core.policy.VocabCoverage
import com.example.core.ports.*
import java.util.UUID

class PostStudentMessageUseCase(
    private val sessions: SessionsPort,
    private val messages: MessagesPort,
    private val idem: IdempotencyPort,
    private val llm: LlmPort,
    private val tx: TxPort,
    private val rateLimiter: RateLimiterPort,
    private val clock: ClockPort
) {
    data class Input(
        val ip: String,
        val sessionId: UUID,
        val studentText: String,
        val idempotencyKey: String?
    )

    sealed class Result {
        data class Ok(val reply: TutorReply) : Result()
        data object SessionNotFound : Result()
        data object RateLimited : Result()
        data object ConflictInProgress : Result()
    }

    suspend fun execute(input: Input): Result {
        val sessionId = input.sessionId

        val rlOk = rateLimiter.allow(
            key = "v1.sessions.messages.ip=${input.ip}.session=$sessionId",
            policy = RateLimiterPort.Policy(limit = 60, windowMs = 60_000)
        )
        if (!rlOk) return Result.RateLimited

        val llmContextLimit = 60
        val coverageStudentLimit = 80

        // Ensure session exists (cheap)
        val sessionSnapshotBefore = sessions.getSessionSnapshot(sessionId, messageLimit = llmContextLimit)
            ?: return Result.SessionNotFound

        val idemKey = input.idempotencyKey?.trim()?.ifBlank { null }

        // 1) Fast path: already computed
        if (idemKey != null) {
            val cached = idem.get(sessionId, idemKey)
            if (cached != null) return Result.Ok(cached)
        }

        val studentText = input.studentText.trim()

        // 2) Claim (and only then persist student message) inside ONE tx
        if (idemKey != null) {
            val claimed = tx.tx {
                val ok = idem.claimInTx(sessionId, idemKey)
                if (!ok) return@tx false

                messages.appendInTx(sessionId, role = "student", content = studentText)
                true
            }

            if (!claimed) {
                // Someone else already claimed it; return cached if already completed.
                val cached = idem.get(sessionId, idemKey)
                if (cached != null) return Result.Ok(cached)

                // Still pending. Let client retry.
                return Result.ConflictInProgress
            }
        } else {
            tx.tx {
                messages.appendInTx(sessionId, role = "student", content = studentText)
            }
        }

        // Snapshot for LLM should include the just-inserted student message
        val sessionSnapshot = sessions.getSessionSnapshot(sessionId, messageLimit = llmContextLimit)
            ?: sessionSnapshotBefore

        // 3) Compute coverage from persisted history (bounded student corpus)
        val studentCorpus = messages.listStudentContentsLast(sessionId, coverageStudentLimit).joinToString("\n")
        val (usedEver, missingEver) = VocabCoverage.compute(studentCorpus, sessionSnapshot.vocab)
        val studentTurns = messages.countByRole(sessionId, role = "student")

        // 4) Call LLM outside tx
        val tutorTextFromLlm = llm.tutorReply(
            session = sessionSnapshot,
            studentText = studentText,
            used = usedEver,
            missing = missingEver
        )

        val shouldShowHint = missingEver.isNotEmpty() && studentTurns >= 2
        val hint = if (shouldShowHint) HintPicker.pick(missingEver) else null

        val tutor = TutorReply(
            tutorText = tutorTextFromLlm,
            hint = hint,
            vocabUsed = usedEver,
            vocabMissing = missingEver
        )

        // 5) Persist tutor message + complete idempotency (if used) in one tx
        tx.tx {
            messages.appendInTx(sessionId, role = "tutor", content = tutor.tutorText)
            if (idemKey != null) {
                idem.completeInTx(sessionId, idemKey, tutor)
            }
        }

        return Result.Ok(tutor)
    }
}
