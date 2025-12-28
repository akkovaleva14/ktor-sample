package com.example

import java.util.concurrent.ConcurrentHashMap

/**
 * Простой in-memory rate limiter (fixed window).
 *
 * Для каждого ключа (например ip/sessionId) считаем число запросов в текущем окне.
 * Если лимит превышен — блокируем.
 *
 * Важно:
 * - in-memory: при рестарте всё обнуляется
 * - multi-instance: лимиты независимы на каждой реплике
 */
object RateLimiter {

    data class Policy(
        val limit: Int,
        val windowMs: Long
    )

    private data class Bucket(
        var windowStartMs: Long,
        var count: Int
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /**
     * @return true если запрос разрешён, false если превышен лимит.
     */
    fun allow(key: String, policy: Policy, nowMs: Long = System.currentTimeMillis()): Boolean {
        val bucket = buckets.compute(key) { _, cur ->
            val b = cur ?: Bucket(windowStartMs = nowMs, count = 0)
            if (nowMs - b.windowStartMs >= policy.windowMs) {
                b.windowStartMs = nowMs
                b.count = 0
            }
            b
        }!!

        // Синхронизируем только на конкретном бакете.
        synchronized(bucket) {
            if (nowMs - bucket.windowStartMs >= policy.windowMs) {
                bucket.windowStartMs = nowMs
                bucket.count = 0
            }
            if (bucket.count >= policy.limit) return false
            bucket.count += 1
            return true
        }
    }
}
