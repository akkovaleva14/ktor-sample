package com.example.shared

/**
 * Обрезает длинный текст для безопасного логирования.
 */
fun String.snip(max: Int = 800): String {
    val s = this.trim()
    return if (s.length <= max) s else s.take(max) + "…(truncated)"
}
