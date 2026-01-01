package com.example.core.policy

/**
 * Phrase-aware покрытие лексики:
 * - если элемент без пробелов => матч по токенам
 * - если элемент с пробелами => матч фразы в нормализованном тексте
 */
object VocabCoverage {

    fun compute(text: String, vocab: List<String>): Pair<List<String>, List<String>> {
        val normalized = normalizeForMatch(text)

        val tokens = normalized
            .split(Regex("""[^a-z0-9']+"""))
            .filter { it.isNotBlank() }
            .toSet()

        fun containsPhrase(phrase: String): Boolean {
            val p = normalizeForMatch(phrase)
            if (p.isBlank()) return false
            val hay = " $normalized "
            val needle = " $p "
            return hay.contains(needle)
        }

        val used = vocab.filter { v ->
            val vv = v.trim()
            if (vv.contains(" ")) containsPhrase(vv) else normalizeForMatch(vv) in tokens
        }

        val missing = vocab.filterNot { v -> used.any { it.equals(v, ignoreCase = true) } }
        return used to missing
    }

    private fun normalizeForMatch(s: String): String {
        return s.lowercase()
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""[^a-z0-9' ]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
