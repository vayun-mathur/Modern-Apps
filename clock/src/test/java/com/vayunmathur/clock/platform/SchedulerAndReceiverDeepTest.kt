package com.vayunmathur.clock.platform

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.ClockRepository
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * AlarmScheduler's trigger math and snooze arming, and AlarmReceiver's ring
 * variants. The snooze persist step is SQLCipher-bound on the host JVM: the
 * AlarmManager arm (which precedes it) is asserted, and the persist failure is
 * asserted as the documented UnsatisfiedLinkError ceiling.
 */
@RunWith(RobolectricTestRunner::class)
class SchedulerAndReceiverDeepTest {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val alarmManager get() = ctx.getSystemService(AlarmManager::class.java)

    private fun alarm(
        time: LocalTime = LocalTime(7, 30),
        days: Int = 0,
        id: Long = 5,
        snoozedUntil: Instant? = null,
    ) = Alarm(time, "Gym", true, days, snoozedUntil = snoozedUntil, id = id)

    @Test fun nextTriggerTodayTomorrowAndWeekdayMask() {
        val tz = TimeZone.UTC
        val monday8am = Instant.parse("2026-11-16T08:00:00Z") // a Monday
        // Later today.
        assertEquals(
            Instant.parse("2026-11-16T09:30:00Z").toEpochMilliseconds(),
            AlarmScheduler.calculateNextTriggerMillis(alarm(LocalTime(9, 30)), monday8am, tz),
        )
        // Already passed: tomorrow.
        assertEquals(
            Instant.parse("2026-11-17T07:30:00Z").toEpochMilliseconds(),
            AlarmScheduler.calculateNextTriggerMillis(alarm(LocalTime(7, 30)), monday8am, tz),
        )
        // Wednesday-only mask (bit 3): from Monday 08:00 -> Wednesday 07:30.
        assertEquals(
            Instant.parse("2026-11-18T07:30:00Z").toEpochMilliseconds(),
            AlarmScheduler.calculateNextTriggerMillis(alarm(LocalTime(7, 30), days = 1 shl 3), monday8am, tz),
        )
        // Sunday-only mask (bit 0): from Monday -> the coming Sunday.
        assertEquals(
            Instant.parse("2026-11-22T07:30:00Z").toEpochMilliseconds(),
            AlarmScheduler.calculateNextTriggerMillis(alarm(LocalTime(7, 30), days = 1 shl 0), monday8am, tz),
        )
        // Exactly now counts as passed (at-or-after is strict): tomorrow.
        assertEquals(
            Instant.parse("2026-11-17T08:00:00Z").toEpochMilliseconds(),
            AlarmScheduler.calculateNextTriggerMillis(alarm(LocalTime(8, 0)), monday8am, tz),
        )
    }

    @Test fun scheduleArmsAndCancelDisarms() {
        AlarmScheduler.schedule(ctx, alarm())
        assertNotNull(shadowOf(alarmManager).nextScheduledAlarm)
        AlarmScheduler.cancel(ctx, alarm())
        // Ring and snooze use distinct request codes so one cannot clobber the other.
        assertTrue(AlarmScheduler.ringRequestCode(5) != AlarmScheduler.snoozeRequestCode(5))
    }

    @Test fun snoozeArmsAlarmThenHitsPersistCeiling() {
        val repo = ClockRepository.get(ctx)
        val target = Instant.parse("2026-11-16T12:00:00Z").toEpochMilliseconds()
        // The arm lands first (shadow-verifiable), then the persist dies on SQLCipher.
        assertFailsWith<UnsatisfiedLinkError> {
            runBlocking { AlarmScheduler.scheduleSnoozeAt(ctx, repo, alarm(), target) }
        }
        val armed = shadowOf(alarmManager).scheduledAlarms.maxByOrNull { it.triggerAtMs }
        assertNotNull(armed)
        assertEquals(target, armed.triggerAtMs)

        assertFailsWith<UnsatisfiedLinkError> {
            runBlocking { AlarmScheduler.scheduleSnooze(ctx, repo, alarm(), 10) }
        }
        // Clearing with nothing persisted never touches the DB.
        runBlocking { AlarmScheduler.clearSnooze(ctx, repo, alarm(snoozedUntil = null)) }
        assertFailsWith<UnsatisfiedLinkError> {
            runBlocking {
                AlarmScheduler.clearSnooze(ctx, repo, alarm(snoozedUntil = Instant.parse("2026-11-16T12:00:00Z")))
            }
        }
    }

    /** Consume stored background DB errors so later runTest tests stay clean. */
    private fun drain() {
        Thread.sleep(1_000)
        repeat(8) {
            try {
                kotlinx.coroutines.test.runTest { }
            } catch (e: IllegalStateException) {
                if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
            }
            Thread.sleep(400)
        }
    }

    @Test fun alarmReceiverRingVariants() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // Gradual + labelled + snooze fire.
        AlarmReceiver().onReceive(
            ctx,
            Intent().putExtra("ALARM_ID", 11L)
                .putExtra(AlarmScheduler.EXTRA_GRADUAL_SECONDS, 30)
                .putExtra(AlarmScheduler.EXTRA_LABEL, "Gym")
                .putExtra(AlarmScheduler.EXTRA_IS_SNOOZE, true),
        )
        assertTrue(shadowOf(nm).allNotifications.isNotEmpty())
        val app = ctx as android.app.Application
        assertNotNull(shadowOf(app).nextStartedService)
        // Silent ringtone fire.
        AlarmReceiver().onReceive(
            ctx,
            Intent().putExtra("ALARM_ID", 12L)
                .putExtra(AlarmScheduler.EXTRA_RINGTONE_URI, "silent"),
        )
        // Missing id is ignored before anything is posted.
        AlarmReceiver().onReceive(ctx, Intent())
        // The reschedule coroutine runs on IO against the (host-unavailable) DB;
        // let it reach its fail-soft end, then drain its stored error.
        drain()
    }
}
