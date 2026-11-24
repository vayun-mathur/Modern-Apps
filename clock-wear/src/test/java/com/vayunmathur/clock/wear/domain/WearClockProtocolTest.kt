package com.vayunmathur.clock.wear.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.encodeToString

class WearClockProtocolTest {

    private fun alarm(
        id: Long,
        hour: Int = 7,
        minute: Int = 30,
        enabled: Boolean = true,
    ) = WearClockAlarm(
        alarmId = id, label = "Wake", hour = hour, minute = minute,
        daysMask = 62, enabled = enabled, phoneNextTriggerMillis = 2_000L,
    )

    @Test
    fun goldenSnapshotJson() {
        val snapshot = WearClockSnapshot(
            generatedAtMillis = 1_000L,
            alarms = listOf(alarm(42L)),
            worldClocks = listOf(WearWorldClock("Tokyo", "Asia/Tokyo")),
            truncated = false,
        )
        assertEquals(
            """{"generatedAtMillis":1000,"alarms":[{"alarmId":42,"label":"Wake","hour":7,""" +
                """"minute":30,"daysMask":62,"enabled":true,"phoneNextTriggerMillis":2000}],""" +
                """"worldClocks":[{"city":"Tokyo","timezoneId":"Asia/Tokyo"}]}""",
            WearClockProtocol.json.encodeToString(snapshot),
        )
    }

    @Test
    fun goldenWatchStateJson() {
        assertEquals(
            """{"appliedGeneratedAtMillis":1000,"alarmCount":2,"armedCount":1,"displayOnlyCount":1}""",
            WearClockProtocol.json.encodeToString(
                WearClockWatchState(
                    appliedGeneratedAtMillis = 1_000L, alarmCount = 2, armedCount = 1,
                    displayOnlyCount = 1,
                ),
            ),
        )
    }

    @Test
    fun snapshotRoundTrips() {
        val snapshot = WearClockSnapshot(
            generatedAtMillis = 9L,
            alarms = listOf(alarm(1L), alarm(2L, hour = 8)),
            worldClocks = listOf(WearWorldClock("Oslo", "Europe/Oslo")),
            truncated = true,
        )
        val decoded = WearClockProtocol.json.decodeFromString<WearClockSnapshot>(
            WearClockProtocol.json.encodeToString(snapshot),
        )
        assertEquals(snapshot, decoded)
    }

    @Test
    fun buildSortsAlarmsKeepsWorldOrderAndCaps() {
        val snapshot = buildWearClockSnapshot(
            alarms = listOf(alarm(3L, hour = 9), alarm(1L, hour = 6), alarm(2L, hour = 6)),
            worldClocks = listOf(
                WearWorldClock("Zurich", "Europe/Zurich"),
                WearWorldClock("Austin", "America/Chicago"),
            ),
            generatedAtMillis = 5L,
        )
        assertEquals(listOf(1L, 2L, 3L), snapshot.alarms.map { it.alarmId })
        assertEquals(listOf("Zurich", "Austin"), snapshot.worldClocks.map { it.city })
        assertFalse(snapshot.truncated)

        val many = buildWearClockSnapshot(
            alarms = (1L..(WearClockProtocol.MAX_ALARMS + 10L)).map { alarm(it) },
            worldClocks = (1..(WearClockProtocol.MAX_WORLD_CLOCKS + 5)).map {
                WearWorldClock("City $it", "UTC")
            },
            generatedAtMillis = 5L,
        )
        assertEquals(WearClockProtocol.MAX_ALARMS, many.alarms.size)
        assertEquals(WearClockProtocol.MAX_WORLD_CLOCKS, many.worldClocks.size)
        assertTrue(many.truncated)
        val bytes = WearClockProtocol.json.encodeToString(many).encodeToByteArray().size
        assertTrue(bytes <= WearClockProtocol.MAX_JSON_BYTES, "snapshot $bytes over cap")
    }

    private fun utcMillis(dateTime: LocalDateTime): Long =
        dateTime.toInstant(TimeZone.UTC).toEpochMilliseconds()

    @Test
    fun nextTriggerOneShotTodayThenTomorrow() {
        val mondayMorning = utcMillis(LocalDateTime(2026, 1, 5, 6, 0))
        assertEquals(
            utcMillis(LocalDateTime(2026, 1, 5, 7, 30)),
            nextWearAlarmTriggerMillis(7, 30, 0, mondayMorning, TimeZone.UTC),
        )
        val mondayAfter = utcMillis(LocalDateTime(2026, 1, 5, 8, 0))
        assertEquals(
            utcMillis(LocalDateTime(2026, 1, 6, 7, 30)),
            nextWearAlarmTriggerMillis(7, 30, 0, mondayAfter, TimeZone.UTC),
        )
    }

    @Test
    fun nextTriggerRecurringUsesSundayBitZero() {
        val mondayAfter = utcMillis(LocalDateTime(2026, 1, 5, 8, 0))
        // Mondays only (bit 1): next is a week later.
        assertEquals(
            utcMillis(LocalDateTime(2026, 1, 12, 7, 30)),
            nextWearAlarmTriggerMillis(7, 30, 1 shl 1, mondayAfter, TimeZone.UTC),
        )
        // Sundays only (bit 0): next is Jan 11.
        assertEquals(
            utcMillis(LocalDateTime(2026, 1, 11, 7, 30)),
            nextWearAlarmTriggerMillis(7, 30, 1 shl 0, mondayAfter, TimeZone.UTC),
        )
        // Weekdays (Mon-Fri, bits 1..5) from Monday after the time: Tuesday.
        assertEquals(
            utcMillis(LocalDateTime(2026, 1, 6, 7, 30)),
            nextWearAlarmTriggerMillis(7, 30, 0b0111110, mondayAfter, TimeZone.UTC),
        )
    }
}
