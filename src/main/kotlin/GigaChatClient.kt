package com.example

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class GigaChatClient(
    private val http: HttpClient,
    private val auth: GigaChatAuth,
    private val model: String = "GigaChat-2-Pro"
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ChatMessage(val role: String, val content: String)

    @Serializable
    data class ChatCompletionReq(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double? = null
    )

    @Serializable
    data class ChatCompletionResp(
        val choices: List<Choice>
    ) {
        @Serializable
        data class Choice(val message: Message) {
            @Serializable
            data class Message(val role: String, val content: String)
        }
    }

    override suspend fun generateOpener(topic: String, vocab: List<String>, level: String?): String {
        val system = """
            Ты дружелюбный репетитор английского. Начни диалог мягко.
            Правила:
            - Верни ТОЛЬКО одну реплику тьютора.
            - 1 короткое предложение + 1 простой вопрос.
            - Не упоминай список слов напрямую.
        """.trimIndent()

        val user = buildString {
            appendLine("Topic: $topic")
            appendLine("Target vocabulary: ${vocab.joinToString(", ")}")
            if (!level.isNullOrBlank()) appendLine("Student level: $level")
        }.trim()

        return chat(listOf(
            ChatMessage("system", system),
            ChatMessage("user", user)
        ), temperature = 0.4).ifBlank {
            "Let’s start with something simple—what comes to mind first?"
        }
    }

    override suspend fun tutorReply(
        session: Session,
        studentText: String,
        used: List<String>,
        missing: List<String>
    ): String {
        val history = session.messages.takeLast(10).joinToString("\n") { m ->
            "${m.role.uppercase()}: ${m.content}"
        }

        val system = """
            Ты дружелюбный репетитор английского.
            Правила:
            - Отвечай как TUTOR, 1–2 предложения.
            - Лучше заканчивай простым вопросом.
            - Будь поддерживающим, не придирайся к грамматике.
            - Цель: мягко подвести ученика к использованию целевой лексики в течение нескольких ходов.
        """.trimIndent()

        val user = buildString {
            appendLine("Topic: ${session.topic}")
            appendLine("Target vocabulary: ${session.vocab.joinToString(", ")}")
            appendLine("Missing in latest student message: ${missing.joinToString(", ")}")
            appendLine()
            appendLine("Recent dialogue:")
            appendLine(history)
            appendLine()
            appendLine("Latest student message:")
            appendLine("STUDENT: $studentText")
            appendLine()
            appendLine("Write the next TUTOR message.")
        }

        return chat(
            messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
            temperature = 0.6
        )
    }

    private suspend fun chat(messages: List<ChatMessage>, temperature: Double?): String {
        val token = auth.getValidToken()

        val resp: HttpResponse = http.post("https://gigachat.devices.sberbank.ru/api/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(ChatCompletionReq(model = model, messages = messages, temperature = temperature))
        }

        val raw = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            throw IllegalStateException("GigaChat chat failed: HTTP ${resp.status.value} body=$raw")
        }

        val parsed = json.decodeFromString(ChatCompletionResp.serializer(), raw)
        return parsed.choices.firstOrNull()?.message?.content?.trim().orEmpty()
    }
}
