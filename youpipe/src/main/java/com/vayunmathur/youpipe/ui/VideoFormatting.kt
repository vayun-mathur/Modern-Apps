package com.vayunmathur.youpipe.ui

import com.vayunmathur.library.util.round
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.network.decodeHtml
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.periodUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

fun uploadTimeAgo(context: android.content.Context, date: Instant): String {
    val now = Clock.System.now()
    val duration = now - date
    if (duration.isNegative()) return context.getString(R.string.time_ago_just_now)
    return when(duration) {
        in 0.minutes..5.minutes -> context.getString(R.string.time_ago_just_now)
        in 5.minutes..1.hours -> context.resources.getQuantityString(R.plurals.time_ago_minutes, duration.inWholeMinutes.toInt(), duration.inWholeMinutes.toInt())
        in 1.hours..24.hours -> context.resources.getQuantityString(R.plurals.time_ago_hours, duration.inWholeHours.toInt(), duration.inWholeHours.toInt())
        else -> uploadTimeAgo(context, date.toLocalDateTime(TimeZone.currentSystemDefault()).date)
    }
}

fun uploadTimeAgo(context: android.content.Context, date: LocalDate): String {
    val period = date.periodUntil(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
    return when {
        period.years > 0 -> context.resources.getQuantityString(R.plurals.time_ago_years, period.years, period.years)
        period.months > 0 -> context.resources.getQuantityString(R.plurals.time_ago_months, period.months, period.months)
        period.days > 0 -> context.resources.getQuantityString(R.plurals.time_ago_days, period.days, period.days)
        else -> context.getString(R.string.time_ago_just_now)
    }
}

fun countString(context: android.content.Context, count: Long): String {
    val digits = count.toString().length
    return when(digits) {
        in 0..3 -> count.toString()
        4 -> context.getString(R.string.count_k_format, (count / 1000.0).round(2).toString())
        5 -> context.getString(R.string.count_k_format, (count / 1000.0).round(1).toString())
        6 -> context.getString(R.string.count_k_format, (count / 1000).toString())
        7 -> context.getString(R.string.count_m_format, (count / 1000000.0).round(2).toString())
        8 -> context.getString(R.string.count_m_format, (count / 1000000.0).round(1).toString())
        9 -> context.getString(R.string.count_m_format, (count / 1000000).toString())
        10 -> context.getString(R.string.count_b_format, (count / 1000000000.0).round(2).toString())
        11 -> context.getString(R.string.count_b_format, (count / 1000000000.0).round(1).toString())
        12 -> context.getString(R.string.count_b_format, (count / 1000000000).toString())
        else -> count.toString()
    }
}

fun String.fromHTML(): String {
    return this.replace("<br>", "\n").decodeHtml()
}

fun getVideoCodecName(codec: String): String {
    return when {
        codec.contains("av01", ignoreCase = true) -> "av1"
        codec.contains("vp9", ignoreCase = true) || codec.contains("vp09", ignoreCase = true) -> "vp9"
        codec.contains("avc", ignoreCase = true) || codec.contains("h264", ignoreCase = true) -> "avc"
        else -> codec
    }
}

fun getAudioCodecName(codec: String): String {
    return when {
        codec.contains("opus", ignoreCase = true) -> "opus"
        codec.contains("mp4a", ignoreCase = true) || codec.contains("aac", ignoreCase = true) -> "aac"
        else -> codec
    }
}
