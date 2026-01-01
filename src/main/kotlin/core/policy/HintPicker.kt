package com.example.core.policy

/**
 * Подсказки (hints) — сознательно простые.
 *
 * Важно: hint — это UI sugar, а не часть "обязательной" логики ответа LLM.
 * Поэтому хранить его в домене можно, но генерировать детерминированно.
 */
object HintPicker {

    fun pick(missingEver: List<String>): String? {
        if (missingEver.isEmpty()) return null

        val priority = listOf("because", "however", "recommend")
        val chosen = missingEver.minByOrNull { w ->
            val idx = priority.indexOf(w.trim().lowercase())
            if (idx >= 0) idx else 999
        } ?: return null

        return when (chosen.trim().lowercase()) {
            "however" -> "Try: \"I liked it. However, ...\""
            "because" -> "Try: \"... because ...\""
            "recommend" -> "Try: \"I recommend ...\""
            else -> "Try to use: $chosen"
        }
    }
}
