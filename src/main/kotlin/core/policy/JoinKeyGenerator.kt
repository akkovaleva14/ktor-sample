package com.example.core.policy

import java.security.SecureRandom

/**
 * JoinKey generator.
 *
 * Читаемый Base32 без неоднозначных символов (нет I, L, O, U).
 * Важно: uniqueness всё равно гарантируется БД (unique join_key).
 */
fun interface JoinKeyGenerator {
    fun generate(length: Int): String

    object Default : JoinKeyGenerator {
        private val alphabet = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
        private val rng = SecureRandom()

        override fun generate(length: Int): String {
            return buildString(length) {
                repeat(length) { append(alphabet[rng.nextInt(alphabet.length)]) }
            }
        }
    }
}

/**
 * Convenience overload with default length.
 *
 * Вынесено в extension, потому что у abstract method в fun interface
 * нельзя иметь значение параметра по умолчанию.
 */
fun JoinKeyGenerator.generate(): String = generate(length = 6)
