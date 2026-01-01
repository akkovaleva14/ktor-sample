package com.example.core.ports

import java.time.Instant

fun interface ClockPort {
    fun now(): Instant

    object System : ClockPort {
        override fun now(): Instant = Instant.now()
    }
}
