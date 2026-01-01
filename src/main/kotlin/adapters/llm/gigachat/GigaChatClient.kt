package com.example.adapters.llm.gigachat

import com.example.core.model.Session
import com.example.core.ports.LlmPingResult
import com.example.core.ports.LlmPort
import com.example.shared.UpstreamException
import com.example.shared.snip
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * LLM-клиент для GigaChat: генерирует стартовую реплику и ответы тьютора.
 *
 * Важно: в подсказки/контекст мы передаём "missing" по сессии (а не по последнему сообщению),
 * чтобы модель не предлагала уже использованные слова заново.
 */
class GigaChatClient(
    private val http: HttpClient,
    private val auth: GigaChatAuth,
    private val model: String = "GigaChat-2-Pro"
) : LlmPort {

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

    /**
     * Быстрая проверка доступности GigaChat:
     * - получаем валидный OAuth-токен
     * - дергаем lightweight endpoint (/models), чтобы проверить авторизацию и доступность API
     *
     * Важно: ping НЕ должен кидать исключения наружу — возвращаем Ok/Fail.
     */
    override suspend fun ping(): LlmPingResult {
        val provider = "gigachat"
        val started = TimeSource.Monotonic.markNow()

        return try {
            val token = auth.getValidToken()

            // Lightweight: список моделей
            val resp: HttpResponse = http.get("https://gigachat.devices.sberbank.ru/api/v1/models") {
                accept(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
            }

            val raw = runCatching { resp.bodyAsText() }.getOrDefault("")

            if (!resp.status.isSuccess()) {
                return LlmPingResult.Fail(
                    provider = provider,
                    reason = "HTTP ${resp.status.value}: ${raw.snip(200)}",
                    httpStatus = resp.status.value
                )
            }

            LlmPingResult.Ok(
                provider = provider,
                latency = started.elapsedNow(),
                details = "models ok"
            )
        } catch (e: UpstreamException) {
            LlmPingResult.Fail(
                provider = provider,
                reason = e.message ?: "UpstreamException",
                httpStatus = e.status.value
            )
        } catch (t: Throwable) {
            LlmPingResult.Fail(
                provider = provider,
                reason = t.message ?: (t::class.simpleName ?: "Unknown error"),
                httpStatus = null
            )
        }
    }

    override suspend fun generateOpener(topic: String, vocab: List<String>, level: String?): String {
        val system = """
            You are a friendly English tutor. Start the conversation gently.
            Rules:
            - Return ONLY one tutor message.
            - Ideally: 1 short sentence + 1 simple question.
            - Do NOT mention the vocabulary list directly.
        """.trimIndent()

        val user = buildString {
            appendLine("Topic: $topic")
            appendLine("Target vocabulary: ${vocab.joinToString(", ")}")
            if (!level.isNullOrBlank()) appendLine("Student level: $level")
        }.trim()

        return chat(
            listOf(ChatMessage("system", system), ChatMessage("user", user)),
            temperature = 0.4
        ).ifBlank {
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
            appendLine("Missing (not used yet in this session): ${missing.joinToString(", ")}")
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
            throw UpstreamException(
                upstream = "gigachat-chat",
                status = resp.status,
                bodySnippet = raw.snip(800),
                message = "GigaChat chat failed: HTTP ${resp.status.value}"
            )
        }

        val parsed = json.decodeFromString(ChatCompletionResp.serializer(), raw)
        return parsed.choices.firstOrNull()?.message?.content?.trim().orEmpty()
    }
}
