package com.example

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ApiTest {

    private val fakeLlm = object : LlmClient {

        override suspend fun generateOpener(topic: String, vocab: List<String>, level: String?): String {
            return "Let’s start simple: what’s your first thought about this topic?"
        }

        override suspend fun tutorReply(
            session: Session,
            studentText: String,
            used: List<String>,
            missing: List<String>
        ): String {
            // В тестах нам важна детерминированность
            return "Good job. Why? (Try: ${missing.joinToString(", ")})"
        }
    }

    @Test
    fun createAssignment_returns201_andJoinKey() = testApplication {
        application { module(llmOverride = fakeLlm) }

        val resp = client.post("/v1/assignments") {
            contentType(ContentType.Application.Json)
            setBody("""{"topic":"Movies","vocab":["because","however"],"level":"A2"}""")
        }

        assertEquals(HttpStatusCode.Created, resp.status)

        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertTrue("assignmentId" in obj)
        assertTrue("joinKey" in obj)

        val joinKey = obj["joinKey"]!!.jsonPrimitive.content
        assertTrue(joinKey.isNotBlank())
        assertTrue(joinKey.length in 4..12) // у нас генерация 6, но пусть тест будет не хрупкий
    }

    @Test
    fun openSession_returns201_andContainsTutorOpenerMessage() = testApplication {
        application { module(llmOverride = fakeLlm) }

        // teacher creates assignment
        val createA = client.post("/v1/assignments") {
            contentType(ContentType.Application.Json)
            setBody("""{"topic":"Movies","vocab":["because","however"]}""")
        }
        assertEquals(HttpStatusCode.Created, createA.status)

        val aObj = Json.parseToJsonElement(createA.bodyAsText()).jsonObject
        val joinKey = aObj["joinKey"]!!.jsonPrimitive.content

        // student opens session by joinKey
        val open = client.post("/v1/sessions/open") {
            contentType(ContentType.Application.Json)
            setBody("""{"joinKey":"$joinKey"}""")
        }
        assertEquals(HttpStatusCode.Created, open.status)

        val openObj = Json.parseToJsonElement(open.bodyAsText()).jsonObject
        assertTrue("sessionId" in openObj)

        val messages = openObj["messages"]!!.jsonArray
        assertTrue(messages.isNotEmpty())

        val first = messages.first().jsonObject
        assertEquals("tutor", first["role"]!!.jsonPrimitive.content)
        assertTrue(first["content"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun history_containsMessagesAfterPosting() = testApplication {
        application { module(llmOverride = fakeLlm) }

        // create assignment
        val createA = client.post("/v1/assignments") {
            contentType(ContentType.Application.Json)
            setBody("""{"topic":"Movies","vocab":["however"]}""")
        }
        val joinKey = Json.parseToJsonElement(createA.bodyAsText())
            .jsonObject["joinKey"]!!.jsonPrimitive.content

        // open session
        val open = client.post("/v1/sessions/open") {
            contentType(ContentType.Application.Json)
            setBody("""{"joinKey":"$joinKey"}""")
        }
        val sessionId = Json.parseToJsonElement(open.bodyAsText())
            .jsonObject["sessionId"]!!.jsonPrimitive.content

        // student posts message
        val postMsg = client.post("/v1/sessions/$sessionId/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"I liked it. However, the ending was sad."}""")
        }
        assertEquals(HttpStatusCode.OK, postMsg.status)

        // history includes tutor opener + student + tutor
        val hist = client.get("/v1/sessions/$sessionId")
        assertEquals(HttpStatusCode.OK, hist.status)

        val obj = Json.parseToJsonElement(hist.bodyAsText()).jsonObject
        val messages = obj["messages"]!!.jsonArray

        assertTrue(messages.size >= 3)

        val roles = messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertTrue("student" in roles)
        assertTrue(roles.count { it == "tutor" } >= 2)
    }
}
