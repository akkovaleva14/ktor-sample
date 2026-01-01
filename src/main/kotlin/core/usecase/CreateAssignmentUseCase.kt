package com.example.core.usecase

import com.example.core.model.Assignment
import com.example.core.policy.JoinKeyGenerator
import com.example.core.policy.generate
import com.example.core.ports.AssignmentsPort
import com.example.core.ports.RateLimiterPort
import com.example.core.ports.TeacherAuthPort
import java.util.UUID

class CreateAssignmentUseCase(
    private val assignments: AssignmentsPort,
    private val teacherAuth: TeacherAuthPort,
    private val rateLimiter: RateLimiterPort,
    private val joinKeyGen: JoinKeyGenerator
) {
    data class Input(
        val presentedBearerToken: String?,
        val ip: String,
        val topic: String,
        val vocab: List<String>,
        val level: String?
    )

    sealed class Result {
        data class Created(val assignment: Assignment) : Result()
        data object Unauthorized : Result()
        data object RateLimited : Result()
    }

    fun execute(input: Input): Result {
        val rlOk = rateLimiter.allow(
            key = "v1.assignments.create.ip=${input.ip}",
            policy = RateLimiterPort.Policy(limit = 10, windowMs = 60_000)
        )
        if (!rlOk) return Result.RateLimited

        if (!teacherAuth.isAllowed(input.presentedBearerToken)) return Result.Unauthorized

        val topic = input.topic.trim()
        val vocab = input.vocab.map { it.trim() }.filter { it.isNotBlank() }
        val level = input.level?.trim()?.ifBlank { null }

        // joinKey generation: DB must enforce unique(join_key), we retry on collision.
        val base = Assignment(
            id = UUID.randomUUID(),
            joinKey = joinKeyGen.generate(),
            topic = topic,
            vocab = vocab,
            level = level
        )

        repeat(10) { attempt ->
            val keyToTry = if (attempt == 0) base.joinKey else joinKeyGen.generate()
            val cur = base.copy(joinKey = keyToTry)

            val ok = runCatching {
                assignments.insert(cur)
                true
            }.getOrElse { e ->
                // Keep it conservative: treat unique collisions as retryable.
                val msg = e.message.orEmpty().lowercase()
                if (msg.contains("duplicate key") || msg.contains("unique")) false else throw e
            }

            if (ok) return Result.Created(cur)
        }

        throw IllegalStateException("Failed to generate unique joinKey after retries")
    }
}
