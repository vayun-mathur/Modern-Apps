package com.vayunmathur.appstore.data.play

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure `Retry-After` parsing (RFC 9110): delay-seconds and HTTP-date forms. */
class PlayRetryAfterParseTest {

    // Sun, 06 Nov 1994 08:49:37 GMT — the RFC's own example instant.
    private val exampleMs = ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneOffset.UTC)
        .toInstant().toEpochMilli()

    @Test
    fun delay_seconds_parse_to_millis() {
        assertEquals(0L, PlayHttpClient.parseRetryAfterMs("0", nowMs = 1_000L))
        assertEquals(120_000L, PlayHttpClient.parseRetryAfterMs("120", nowMs = 1_000L))
        assertEquals(5_000L, PlayHttpClient.parseRetryAfterMs(" 5 ", nowMs = 1_000L))
    }

    @Test
    fun imf_fixdate_parses_relative_to_now() {
        val header = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(exampleMs), ZoneOffset.UTC),
        )
        assertEquals(37_000L, PlayHttpClient.parseRetryAfterMs(header, nowMs = exampleMs - 37_000L))
        // The RFC's literal example string parses the same way.
        assertEquals(
            37_000L,
            PlayHttpClient.parseRetryAfterMs("Sun, 06 Nov 1994 08:49:37 GMT", nowMs = exampleMs - 37_000L),
        )
    }

    @Test
    fun past_http_date_and_garbage_yield_zero() {
        assertEquals(0L, PlayHttpClient.parseRetryAfterMs("Sun, 06 Nov 1994 08:49:37 GMT", nowMs = exampleMs + 1))
        assertEquals(0L, PlayHttpClient.parseRetryAfterMs("soon", nowMs = 0L))
        assertEquals(0L, PlayHttpClient.parseRetryAfterMs(null, nowMs = 0L))
        assertEquals(0L, PlayHttpClient.parseRetryAfterMs("", nowMs = 0L))
        assertEquals(0L, PlayHttpClient.parseRetryAfterMs("-5", nowMs = 0L))
    }

    @Test
    fun asctime_form_parses_as_gmt() {
        // Sun Nov  6 08:49:37 1994 — same instant, obsolete asctime form.
        assertEquals(10_000L, PlayHttpClient.parseRetryAfterMs("Sun Nov  6 08:49:37 1994", nowMs = exampleMs - 10_000L))
    }
}
