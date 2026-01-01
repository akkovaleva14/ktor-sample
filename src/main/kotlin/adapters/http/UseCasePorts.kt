package com.example.adapters.http

import com.example.core.model.SessionSummary
import com.example.core.usecase.CreateAssignmentUseCase
import com.example.core.usecase.DeleteSessionUseCase
import com.example.core.usecase.GetAssignmentUseCase
import com.example.core.usecase.GetSessionUseCase
import com.example.core.usecase.ListSessionsUseCase
import com.example.core.usecase.OpenSessionUseCase
import com.example.core.usecase.PostStudentMessageUseCase
import java.util.UUID

interface CreateAssignment {
    fun execute(input: CreateAssignmentUseCase.Input): CreateAssignmentUseCase.Result
}

interface GetAssignment {
    fun execute(id: UUID): GetAssignmentUseCase.Result
}

interface OpenSession {
    suspend fun execute(input: OpenSessionUseCase.Input): OpenSessionUseCase.Result
}

interface PostStudentMessage {
    suspend fun execute(input: PostStudentMessageUseCase.Input): PostStudentMessageUseCase.Result
}

interface GetSession {
    fun execute(id: UUID): GetSessionUseCase.Result
}

interface ListSessions {
    fun execute(limit: Int, offset: Int): List<SessionSummary>
}

interface DeleteSession {
    fun execute(id: UUID): Boolean
}
