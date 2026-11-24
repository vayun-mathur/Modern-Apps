package com.vayunmathur.calendar.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class WearCalendarProtocolTest {

    private fun event(
        id: Long,
        start: Long,
        end: Long = start + 3_600_000L,
        title: String = "Event $id",
        description: String = "",
    ) = WearCalendarEvent(
        eventId = id,
        title = title,
        location = "Room $id",
        description = description,
        calendarName = "Personal",
        color = 7,
        startMillis = start,
        endMillis = end,
        timezone = "UTC",
    )

    @Test
    fun goldenSnapshotJson() {
        val snapshot = WearCalendarSnapshot(
            generatedAtMillis = 1_000L,
            windowStartMillis = 2_000L,
            windowEndMillis = 3_000L,
            events = listOf(event(42L, 2_500L)),
            truncated = false,
        )
        assertEquals(
            """{"generatedAtMillis":1000,"windowStartMillis":2000,"windowEndMillis":3000,""" +
                """"events":[{"eventId":42,"title":"Event 42","location":"Room 42",""" +
                """"calendarName":"Personal","color":7,"startMillis":2500,""" +
                """"endMillis":3602500}]}""",
            WearCalendarProtocol.json.encodeToString(snapshot),
        )
    }

    @Test
    fun snapshotRoundTrips() {
        val snapshot = WearCalendarSnapshot(
            generatedAtMillis = 9L,
            windowStartMillis = 10L,
            windowEndMillis = 20L,
            events = listOf(event(1L, 12L), event(2L, 14L)),
            truncated = true,
        )
        val decoded = WearCalendarProtocol.json.decodeFromString<WearCalendarSnapshot>(
            WearCalendarProtocol.json.encodeToString(snapshot),
        )
        assertEquals(snapshot, decoded)
    }

    @Test
    fun buildKeepsOnlyWindowAndSortsByStart() {
        val snapshot = buildWearSnapshot(
            candidates = listOf(
                event(3L, 5_000L),
                event(1L, 3_000L),
                event(9L, 500L, end = 900L), // over before window
                event(8L, 9_500L), // starts after window
            ),
            generatedAtMillis = 1_000L,
            windowStartMillis = 1_000L,
            windowEndMillis = 9_000L,
        )
        assertEquals(listOf(1L, 3L), snapshot.events.map { it.eventId })
        assertFalse(snapshot.truncated)
    }

    @Test
    fun buildCapsEventCountAndMarksTruncated() {
        val candidates = (1L..(WearCalendarProtocol.MAX_EVENTS + 50L)).map {
            event(it, 1_000L + it * 1_000L)
        }
        val snapshot = buildWearSnapshot(candidates, 0L, 0L, Long.MAX_VALUE)
        assertEquals(WearCalendarProtocol.MAX_EVENTS, snapshot.events.size)
        assertTrue(snapshot.truncated)
        // Earliest kept: today's agenda survives longest.
        assertEquals(1L, snapshot.events.first().eventId)
    }

    @Test
    fun buildCapsFieldLengthsAndTotalBytes() {
        val longTitle = "x".repeat(WearCalendarProtocol.MAX_TITLE_CHARS + 100)
        val candidates = (1L..300L).map {
            event(it, 1_000L + it * 1_000L, title = longTitle, description = "d".repeat(500))
        }
        val snapshot = buildWearSnapshot(candidates, 0L, 0L, Long.MAX_VALUE)
        assertTrue(snapshot.events.all {
            it.title.length == WearCalendarProtocol.MAX_TITLE_CHARS &&
                it.description.length == WearCalendarProtocol.MAX_DESCRIPTION_CHARS
        })
        val bytes = WearCalendarProtocol.json.encodeToString(snapshot).encodeToByteArray().size
        assertTrue(bytes <= WearCalendarProtocol.MAX_JSON_BYTES, "snapshot $bytes bytes over cap")
        assertTrue(snapshot.truncated)
    }

    @Test
    fun capabilityAbsentMeansNoCompanion() {
        assertFalse(wearCompanionPresent(emptyList()))
        assertTrue(wearCompanionPresent(listOf("node-1")))
    }
}
