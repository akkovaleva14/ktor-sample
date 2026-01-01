package com.example.smoke

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("smoke")
class SmokeE2ETest {

    private val base = System.getenv("SMOKE_BASE_URL").orEmpty()
    private val teacherToken = System.getenv("SMOKE_TEACHER_TOKEN").orEmpty()

    private val client = HttpClient(CIO) { expectSuccess = false }

    @Test
    fun `happy path - assignment to session to idempotent message`() = kotlinx.coroutines.runBlocking {
        assumeTrue(base.isNotBlank(), "SMOKE_BASE_URL not set")
        assumeTrue(teacherToken.isNotBlank(), "SMOKE_TEACHER_TOKEN not set")

        // health
        val health = client.get("$base/health")
        assertEquals(HttpStatusCode.OK, health.status)

        // create assignment
        val created = client.post("$base/v1/assignments") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $teacherToken")
            setBody("""{"topic":"Smoke","vocab":["however"],"level":"A2"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)

        val createdJson = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val joinKey = createdJson["joinKey"]!!.jsonPrimitive.content
        assertTrue(joinKey.isNotBlank())

        // open session
        val open = client.post("$base/v1/sessions/open") {
            contentType(ContentType.Application.Json)
            setBody("""{"joinKey":"$joinKey"}""")
        }
        assertEquals(HttpStatusCode.Created, open.status)

        val openJson = Json.parseToJsonElement(open.bodyAsText()).jsonObject
        val sessionId = openJson["sessionId"]!!.jsonPrimitive.content

        // post message twice with same idempotency key
        val idem = "smoke-${UUID.randomUUID()}"
        val m1 = client.post("$base/v1/sessions/$sessionId/messages") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idem)
            setBody("""{"text":"smoke"}""")
        }
        assertEquals(HttpStatusCode.OK, m1.status)
        val body1 = m1.bodyAsText()

        val m2 = client.post("$base/v1/sessions/$sessionId/messages") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idem)
            setBody("""{"text":"smoke"}""")
        }
        assertEquals(HttpStatusCode.OK, m2.status)
        val body2 = m2.bodyAsText()

        assertEquals(body1, body2)
    }
}
