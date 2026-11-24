package com.vayunmathur.clock.wear.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WearAlarmStateTest {

    private val now = 1_000_000L
    private fun alarm(enabled: Boolean = true) = WearClockAlarm(
        alarmId = 7L, label = "Wake", hour = 7, minute = 30, enabled = enabled,
    )

    @Test
    fun enabledWithoutArmingProofIsDisplayOnlyNeverArmed() {
        val state = resolveWearAlarmDisplay(alarm(), armed = null, failure = null, nowMillis = now)
        assertEquals(
            WearAlarmDisplayState.DisplayOnly(DisplayOnlyReason.NOT_YET_ARMED), state,
        )
    }

    @Test
    fun disabledIsDisplayOnlyEvenWithStaleProof() {
        val state = resolveWearAlarmDisplay(
            alarm(enabled = false),
            armed = ArmedAlarmRecord(7L, now + 60_000L, exact = true),
            failure = null,
            nowMillis = now,
        )
        assertEquals(
            WearAlarmDisplayState.DisplayOnly(DisplayOnlyReason.DISABLED_ON_PHONE), state,
        )
    }

    @Test
    fun successfulArmingProofIsArmedWithItsExactness() {
        assertEquals(
            WearAlarmDisplayState.Armed(now + 60_000L, exact = true),
            resolveWearAlarmDisplay(
                alarm(), ArmedAlarmRecord(7L, now + 60_000L, exact = true), null, now,
            ),
        )
        assertEquals(
            WearAlarmDisplayState.Armed(now + 60_000L, exact = false),
            resolveWearAlarmDisplay(
                alarm(), ArmedAlarmRecord(7L, now + 60_000L, exact = false), null, now,
            ),
        )
    }

    @Test
    fun staleOrForeignProofDoesNotArm() {
        // Trigger already in the past: the schedule it proved has fired/been lost.
        val stale = resolveWearAlarmDisplay(
            alarm(), ArmedAlarmRecord(7L, now - 1L, exact = true), null, now,
        )
        assertTrue(stale is WearAlarmDisplayState.DisplayOnly)
        // Proof for a different alarm must not leak onto this one.
        val foreign = resolveWearAlarmDisplay(
            alarm(), ArmedAlarmRecord(99L, now + 60_000L, exact = true), null, now,
        )
        assertEquals(
            WearAlarmDisplayState.DisplayOnly(DisplayOnlyReason.NOT_YET_ARMED), foreign,
        )
    }

    @Test
    fun recordedFailureSurfacesAsDisplayOnlyWithReason() {
        val state = resolveWearAlarmDisplay(
            alarm(), armed = null,
            failure = ArmFailureRecord(7L, DisplayOnlyReason.SCHEDULING_FAILED),
            nowMillis = now,
        )
        assertEquals(
            WearAlarmDisplayState.DisplayOnly(DisplayOnlyReason.SCHEDULING_FAILED), state,
        )
    }

    @Test
    fun countsOnlyEnabledAlarmsAndSplitByProof() {
        val alarms = listOf(
            alarm(),
            WearClockAlarm(alarmId = 8L, hour = 8, minute = 0, enabled = true),
            WearClockAlarm(alarmId = 9L, hour = 9, minute = 0, enabled = false),
        )
        val (armed, displayOnly) = armedAndDisplayOnlyCounts(
            alarms,
            armedRecords = listOf(ArmedAlarmRecord(7L, now + 60_000L, exact = true)),
            failures = emptyList(),
            nowMillis = now,
        )
        assertEquals(1, armed)
        assertEquals(1, displayOnly) // alarm 8 has no proof; disabled 9 counts nowhere
    }
}
