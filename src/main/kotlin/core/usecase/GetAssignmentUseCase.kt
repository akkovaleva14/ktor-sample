package com.example.core.usecase

import com.example.core.model.Assignment
import com.example.core.ports.AssignmentsPort
import java.util.UUID

class GetAssignmentUseCase(
    private val assignments: AssignmentsPort
) {
    sealed class Result {
        data class Found(val assignment: Assignment) : Result()
        data object NotFound : Result()
    }

    fun execute(id: UUID): Result {
        val a = assignments.getById(id) ?: return Result.NotFound
        return Result.Found(a)
    }
}
