package com.vayunmathur.clock.platform

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.ClockRepository
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.runner.RunWith
import com.vayunmathur.clock.FakeAndroidKeyStore
import org.junit.Before
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Tests for [AlarmScheduler]: trigger math, request codes, AlarmManager arming and snoozes. */
@RunWith(RobolectricTestRunner::class)
class AlarmSchedulerTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()


    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun alarm(days: Int = 0, hour: Int = 23, minute: Int = 59, id: Long = 42) = Alarm(
        time = LocalTime(hour, minute), name = "wake", enabled = true, days = days, id = id,
    )

    @Test
    fun requestCodesAreDistinctPerAlarmAndKind() {
        assertEquals(84, AlarmScheduler.ringRequestCode(42))
        assertEquals(85, AlarmScheduler.snoozeRequestCode(42))
        assertTrue(AlarmScheduler.ringRequestCode(1) != AlarmScheduler.ringRequestCode(2))
    }

    @Test
    fun oneShotTriggerIsInFutureWithinTwoDays() {
        val now = Clock.System.now().toEpochMilliseconds()
        val trigger = AlarmScheduler.calculateNextTriggerMillis(alarm(days = 0, hour = 0, minute = 0))
        assertTrue(trigger > now, "trigger $trigger <= now $now")
        assertTrue(trigger <= now + 2 * 24 * 3600_000L)
    }

    @Test
    fun recurringTriggerLandsOnSelectedWeekday() {
        // Every-day mask must fire within 24h (+ slack for the time-of-day already passing).
        val now = Clock.System.now().toEpochMilliseconds()
        val trigger = AlarmScheduler.calculateNextTriggerMillis(alarm(days = 0b1111111, hour = 23, minute = 59))
        assertTrue(trigger > now)
        assertTrue(trigger <= now + 25 * 3600_000L, "daily alarm trigger too far: ${trigger - now}")
    }

    private val newYork = TimeZone.of("America/New_York")

    private fun at(date: LocalDate, time: LocalTime, zone: TimeZone) =
        LocalDateTime(date, time).toInstant(zone)

    @Test
    fun dailyAlarmAcrossSpringForwardKeepsWallClockTime() {
        // Sat 2026-03-07 10:00 EST; the 09:00 daily alarm already passed today,
        // so it must fire Sun 09:00 EDT (23h later), not 10:00 after a +24h step.
        val now = at(LocalDate(2026, 3, 7), LocalTime(10, 0), newYork)
        val trigger = AlarmScheduler.calculateNextTriggerMillis(alarm(days = 0b1111111, hour = 9, minute = 0), now, newYork)
        val expected = at(LocalDate(2026, 3, 8), LocalTime(9, 0), newYork)
        assertEquals(expected.toEpochMilliseconds(), trigger)
        assertEquals(LocalTime(9, 0), kotlin.time.Instant.fromEpochMilliseconds(trigger).toLocalDateTime(newYork).time)
    }

    @Test
    fun dailyAlarmAcrossFallBackKeepsWallClockTime() {
        // Sat 2026-10-31 10:00 EDT; next fire is Sun 09:00 EST (25h later),
        // not 08:00 after a +24h step.
        val now = at(LocalDate(2026, 10, 31), LocalTime(10, 0), newYork)
        val trigger = AlarmScheduler.calculateNextTriggerMillis(alarm(days = 0b1111111, hour = 9, minute = 0), now, newYork)
        val expected = at(LocalDate(2026, 11, 1), LocalTime(9, 0), newYork)
        assertEquals(expected.toEpochMilliseconds(), trigger)
        assertEquals(LocalTime(9, 0), kotlin.time.Instant.fromEpochMilliseconds(trigger).toLocalDateTime(newYork).time)
    }

    @Test
    fun recurringAlarmAfterDstJumpLandsOnSelectedWeekday() {
        // Only Mondays selected; from Sat 2026-03-07 the next fire is Mon 2026-03-09 09:00 EDT.
        val mondayBit = 1 shl 1
        val now = at(LocalDate(2026, 3, 7), LocalTime(10, 0), newYork)
        val trigger = AlarmScheduler.calculateNextTriggerMillis(alarm(days = mondayBit, hour = 9, minute = 0), now, newYork)
        assertEquals(at(LocalDate(2026, 3, 9), LocalTime(9, 0), newYork).toEpochMilliseconds(), trigger)
    }

    @Test
    fun oneShotInSpringForwardGapResolvesForward() {
        // 02:30 does not exist on 2026-03-08 in New York; the alarm must still
        // fire that morning (shifted to 03:30 EDT), never throw or skip a day.
        val now = at(LocalDate(2026, 3, 7), LocalTime(10, 0), newYork)
        val trigger = AlarmScheduler.calculateNextTriggerMillis(alarm(days = 0, hour = 2, minute = 30), now, newYork)
        val local = kotlin.time.Instant.fromEpochMilliseconds(trigger).toLocalDateTime(newYork)
        assertEquals(LocalDate(2026, 3, 8), local.date)
        assertEquals(LocalTime(3, 30), local.time)
    }

    @Test
    fun scheduleArmsAlarmClockAndCancelDisarms() {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val shadow = shadowOf(am)
        AlarmScheduler.schedule(ctx, alarm())
        assertNotNull(shadow.nextScheduledAlarm, "schedule should arm an AlarmClock entry")
        AlarmScheduler.cancel(ctx, alarm())
    }

}
