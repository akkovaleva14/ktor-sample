package com.example.core.ports

/**
 * Rate limiter порт (в текущей реализации — in-memory).
 *
 * Важно:
 * - in-memory: при рестарте всё обнуляется
 * - multi-instance: лимиты независимы на каждой реплике
 */
interface RateLimiterPort {
    data class Policy(val limit: Int, val windowMs: Long)

    /**
     * @return true если запрос разрешён, false если превышен лимит.
     */
    fun allow(key: String, policy: Policy, nowMs: Long = System.currentTimeMillis()): Boolean
}
