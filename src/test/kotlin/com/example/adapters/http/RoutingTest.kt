package com.example.adapters.http

import com.example.core.model.Session
import com.example.core.model.SessionSummary
import com.example.core.ports.LlmPingResult
import com.example.core.ports.LlmPort
import com.example.core.ports.TxPort
import com.example.core.usecase.CreateAssignmentUseCase
import com.example.core.usecase.GetAssignmentUseCase
import com.example.core.usecase.GetSessionUseCase
import com.example.core.usecase.OpenSessionUseCase
import com.example.core.usecase.PostStudentMessageUseCase
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutingTest {

    @Test
    fun `POST v1 assignments without Authorization returns 401`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                    }
                )
            }

            val txStub = object : TxPort {
                override fun <T> tx(block: () -> T): T {
                    return block()
                }
            }

            val llmStub = object : LlmPort {
                override suspend fun generateOpener(topic: String, vocab: List<String>, level: String?): String {
                    error("not used")
                }

                override suspend fun tutorReply(
                    session: Session,
                    studentText: String,
                    used: List<String>,
                    missing: List<String>
                ): String {
                    error("not used")
                }

                override suspend fun ping(): LlmPingResult {
                    return LlmPingResult.Ok(provider = "stub")
                }
            }

            Routing.install(
                app = this,
                tx = txStub,
                llm = llmStub,
                createAssignment = object : CreateAssignment {
                    override fun execute(input: CreateAssignmentUseCase.Input): CreateAssignmentUseCase.Result {
                        return CreateAssignmentUseCase.Result.Unauthorized
                    }
                },
                getAssignment = object : GetAssignment {
                    override fun execute(id: UUID): GetAssignmentUseCase.Result {
                        return GetAssignmentUseCase.Result.NotFound
                    }
                },
                openSession = object : OpenSession {
                    override suspend fun execute(input: OpenSessionUseCase.Input): OpenSessionUseCase.Result {
                        return OpenSessionUseCase.Result.InvalidJoinKey
                    }
                },
                postStudentMessage = object : PostStudentMessage {
                    override suspend fun execute(input: PostStudentMessageUseCase.Input): PostStudentMessageUseCase.Result {
                        return PostStudentMessageUseCase.Result.SessionNotFound
                    }
                },
                getSession = object : GetSession {
                    override fun execute(id: UUID): GetSessionUseCase.Result {
                        return GetSessionUseCase.Result.NotFound
                    }
                },
                listSessions = object : ListSessions {
                    override fun execute(limit: Int, offset: Int): List<SessionSummary> {
                        return emptyList()
                    }
                },
                deleteSession = object : DeleteSession {
                    override fun execute(id: UUID): Boolean {
                        return false
                    }
                }
            )
        }

        val res: HttpResponse = client.post("/v1/assignments") {
            contentType(ContentType.Application.Json)
            setBody("""{"topic":"Smoke","vocab":["however"],"level":"A2"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertTrue(res.bodyAsText().contains("auth_error"))
    }
}
