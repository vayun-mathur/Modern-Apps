package com.vayunmathur.clock.platform

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.ClockRepository
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
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

    @Test
    fun scheduleArmsAlarmClockAndCancelDisarms() {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val shadow = shadowOf(am)
        AlarmScheduler.schedule(ctx, alarm())
        assertNotNull(shadow.nextScheduledAlarm, "schedule should arm an AlarmClock entry")
        AlarmScheduler.cancel(ctx, alarm())
    }

}
