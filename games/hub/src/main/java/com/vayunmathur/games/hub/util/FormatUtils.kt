package com.vayunmathur.games.hub.util

import androidx.compose.ui.graphics.Color
import java.util.concurrent.TimeUnit

fun formatPlaytime(ms: Long): String {
    if (ms <= 0) return "0m"
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

fun formatDurationMs(ms: Long): String {
    if (ms <= 0) return "0s"
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms)
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - timestamp
    if (diff < 0) return "now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    }
}

fun tierColor(tier: String): Color = when (tier.uppercase()) {
    "PLATINUM" -> Color(0xFF9C27B0)
    "GOLD" -> Color(0xFFFFD700)
    "SILVER" -> Color(0xFF9E9E9E)
    else -> Color(0xFFCD7F32)
}
