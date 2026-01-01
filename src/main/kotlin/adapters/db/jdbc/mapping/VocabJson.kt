package com.example.adapters.db.jdbc.mapping

import kotlinx.serialization.json.Json

/**
 * JSON serialization for vocab list.
 *
 * Храним в adapters/db, потому что это деталь хранения (jsonb) и не должна жить в core.
 */
private val json = Json { ignoreUnknownKeys = true }

fun vocabToJson(vocab: List<String>): String = json.encodeToString(vocab)
fun vocabFromJson(raw: String): List<String> = json.decodeFromString(raw)
