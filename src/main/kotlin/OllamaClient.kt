package com.example

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OllamaClient(
    private val http: HttpClient,
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "qwen2.5:7b"
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class OllamaGenerateReq(
        val model: String,
        val prompt: String,
        val stream: Boolean = false
    )

    override suspend fun generateOpener(topic: String, vocab: List<String>, level: String?): String {
        val prompt = buildString {
            appendLine("You are a friendly English tutor starting a dialogue with a student.")
            appendLine("The teacher provided the topic and target vocabulary, but do not force vocabulary immediately.")
            appendLine()
            appendLine("Topic: $topic")
            appendLine("Target vocabulary: ${vocab.joinToString(", ")}")
            if (!level.isNullOrBlank()) appendLine("Student level: $level")
            appendLine()
            appendLine("Rules:")
            appendLine("- Write ONLY the tutor's opening line.")
            appendLine("- Keep it gentle and not 'hardcore'.")
            appendLine("- 1 short sentence + 1 simple question is ideal.")
            appendLine("- Do NOT ask for long explanations yet.")
            appendLine("- Do NOT mention the vocabulary list.")
            appendLine()
            appendLine("Now generate the opening line.")
        }

        return ollamaGenerateText(prompt).ifBlank {
            // безопасный фоллбек, если LLM по какой-то причине не вернул текст
            "Let’s start with something simple—what comes to mind first?"
        }
    }

    override suspend fun tutorReply(
        session: Session,
        studentText: String,
        used: List<String>,
        missing: List<String>
    ): String {
        val history = session.messages
            .takeLast(10)
            .joinToString("\n") { m -> "${m.role.uppercase()}: ${m.content}" }

        val prompt = buildString {
            appendLine("You are a friendly English tutor having a dialogue with a student.")
            appendLine("Your job is to guide the student to naturally use the target vocabulary over multiple turns.")
            appendLine()
            appendLine("Topic: ${session.topic}")
            appendLine("Target vocabulary: ${session.vocab.joinToString(", ")}")
            appendLine("Missing (not used in the latest student message): ${missing.joinToString(", ")}")
            appendLine()
            appendLine("Dialogue rules:")
            appendLine("- Reply as TUTOR only.")
            appendLine("- Keep it short: 1–2 sentences.")
            appendLine("- Prefer a follow-up question to keep the dialogue going.")
            appendLine("- Do NOT show explicit 'hint' templates.")
            appendLine("- Be encouraging. Do not over-correct grammar.")
            appendLine()
            appendLine("Nudging strategy:")
            appendLine("- If 'because' is missing, ask for a reason using a simple 'Why?' question.")
            appendLine("- If 'however' is missing, ask for a contrast: a downside / exception / different viewpoint.")
            appendLine("- If 'recommend' is missing, ask what they would recommend to a friend.")
            appendLine()
            appendLine("Recent dialogue:")
            appendLine(history)
            appendLine()
            appendLine("Latest student message:")
            appendLine("STUDENT: $studentText")
            appendLine()
            appendLine("Now write the next TUTOR message.")
        }

        return ollamaGenerateText(prompt)
    }

    private suspend fun ollamaGenerateText(prompt: String): String {
        val resp: HttpResponse = http.post("$baseUrl/api/generate") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(OllamaGenerateReq(model = model, prompt = prompt, stream = false))
        }

        val raw = resp.bodyAsText()

        // Ollama often returns NDJSON: each line is a JSON object with optional "response" chunk.
        // We accumulate all "response" fields across lines. Final line often has response="".
        val out = StringBuilder()

        val lines = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        if (lines.isEmpty()) return ""

        for (line in lines) {
            val elem = runCatching { json.parseToJsonElement(line) }.getOrNull() ?: continue
            val obj = elem.jsonObject
            val chunk = obj["response"]?.jsonPrimitive?.content
            if (!chunk.isNullOrEmpty()) out.append(chunk)
        }

        return out.toString().trim()
    }
}
