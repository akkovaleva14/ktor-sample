package com.example.db

import com.example.MessageDto
import com.example.Session
import com.example.Msg
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }

fun vocabToJson(vocab: List<String>): String = json.encodeToString(vocab)
fun vocabFromJson(raw: String): List<String> = json.decodeFromString(raw)
