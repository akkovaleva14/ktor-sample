package com.example.app

import io.ktor.util.AttributeKey

/**
 * Ключи атрибутов приложения.
 *
 * Храним здесь, чтобы не создавать AttributeKey в разных местах с разными именами.
 */
object AppAttributes {
    val StartedAtMs: AttributeKey<Long> = AttributeKey("appStartedAtMs")
}
