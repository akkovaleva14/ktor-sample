package com.example.adapters.db.jdbc

import java.time.Duration

fun durationToPgInterval(d: Duration): String {
    require(!d.isNegative && !d.isZero) { "duration must be > 0" }

    val totalSeconds = d.seconds
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60

    return buildString {
        if (days != 0L) append("$days days ")
        if (hours != 0L) append("$hours hours ")
        if (minutes != 0L) append("$minutes minutes ")
        if (seconds != 0L || isEmpty()) append("$seconds seconds")
    }.trim()
}
