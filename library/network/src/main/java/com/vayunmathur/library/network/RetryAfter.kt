package com.vayunmathur.library.network

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one `Retry-After` parser for the repo (RFC 9110 §10.2.3).
 *
 * Moved here from appstore's `PlayHttpClient` so every retry path reads the
 * header the same way; appstore delegates to this instead of keeping a second
 * parser, and nothing else should grow a third.
 */
object RetryAfter {

    private const val MILLIS_PER_SECOND = 1_000L

    /** RFC 850 (obsolete) HTTP-date, e.g. `Sunday, 06-Nov-94 08:49:37 GMT`. */
    private val RFC_850_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss z", Locale.US)

    /** ANSI C asctime (obsolete) HTTP-date, e.g. `Sun Nov  6 08:49:37 1994` (GMT assumed). */
    private val ASCTIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US)

    /**
     * Parses a `Retry-After` header value to milliseconds from [nowMs]:
     * either delay-seconds (`120`) or an HTTP-date
     * (`Sun, 06 Nov 1994 08:49:37 GMT`, in any of the three formats a
     * recipient must accept). A date in the past, an absent header and an
     * unparseable value all yield 0, which the caller reads as "no server
     * delay named" and falls back to exponential backoff for.
     */
    fun parseMs(header: String?, nowMs: Long = System.currentTimeMillis()): Long {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
        value.toLongOrNull()?.let { seconds ->
            return if (seconds >= 0) seconds * MILLIS_PER_SECOND else 0L
        }
        val dateMs = parseHttpDateMs(value) ?: return 0L
        return (dateMs - nowMs).coerceAtLeast(0L)
    }

    private fun parseHttpDateMs(value: String): Long? {
        // Collapse runs of spaces: asctime pads a single-digit day with one.
        val normalized = value.replace(Regex(" +"), " ")
        val formatters = listOf(DateTimeFormatter.RFC_1123_DATE_TIME, RFC_850_FORMAT, ASCTIME_FORMAT)
        for (formatter in formatters) {
            try {
                return ZonedDateTime.parse(normalized, formatter).toInstant().toEpochMilli()
            } catch (_: DateTimeException) {
                // Try the next sanctioned format.
            }
        }
        // asctime carries no zone; RFC 9110 dates are GMT, so retry it as UTC.
        return try {
            LocalDateTime.parse(normalized, ASCTIME_FORMAT)
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }
}
