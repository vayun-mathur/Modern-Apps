package com.vayunmathur.games.hub.util

import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.format.formatRelativeAge

fun formatPlaytime(ms: Long): String {
    if (ms <= 0) return "0m"
    val hours = ms.milliseconds.inWholeHours
    val minutes = ms.milliseconds.inWholeMinutes % MINUTES_PER_HOUR
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

fun formatDurationMs(ms: Long): String {
    if (ms <= 0) return "0s"
    val seconds = ms.milliseconds.inWholeSeconds
    val minutes = seconds / MINUTES_PER_HOUR
    val hours = minutes / MINUTES_PER_HOUR
    return when {
        hours > 0 -> "${hours}h ${minutes % MINUTES_PER_HOUR}m"
        minutes > 0 -> "${minutes}m ${seconds % MINUTES_PER_HOUR}s"
        else -> "${seconds}s"
    }
}

fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    // A non-positive timestamp took the date fallback before centralization
    // (it is far past the week band), so keep that instead of the shared
    // unknown-label.
    if (timestamp <= 0L) {
        return java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
    }
    return formatRelativeAge(
        atMillis = timestamp,
        nowMillis = now,
        futureLabel = "now",
        maxDays = DAYS_PER_WEEK.toLong(),
        olderLabel = { atMillis ->
            java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
                .format(java.util.Date(atMillis))
        },
    )
}

fun tierColor(tier: String): Color = when (tier.uppercase()) {
    "PLATINUM" -> Color(PLATINUM_ARGB)
    "GOLD" -> Color(GOLD_ARGB)
    "SILVER" -> Color(SILVER_ARGB)
    else -> Color(BRONZE_ARGB)
}

private const val MINUTES_PER_HOUR = 60
private const val DAYS_PER_WEEK = 7

private const val PLATINUM_ARGB = 0xFF9C27B0
private const val GOLD_ARGB = 0xFFFFD700
private const val SILVER_ARGB = 0xFF9E9E9E
private const val BRONZE_ARGB = 0xFFCD7F32
