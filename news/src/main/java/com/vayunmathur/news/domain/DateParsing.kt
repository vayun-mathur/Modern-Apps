package com.vayunmathur.news.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Non-throwing date parsing for sitemap/RSS timestamps; null if unrecognised. */
object DateParsing {
    private val parsers: List<(String) -> Long> = listOf(
        { Instant.parse(it).toEpochMilli() },
        { OffsetDateTime.parse(it).toInstant().toEpochMilli() },
        { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() },
        {
            val fmt = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
            ZonedDateTime.parse(it, fmt).toInstant().toEpochMilli()
        },
        {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
            LocalDateTime.parse(it, fmt).toInstant(ZoneOffset.UTC).toEpochMilli()
        },
    )

    fun parseMillis(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (parser in parsers) {
            val parsed = runCatching { parser(text) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
}
