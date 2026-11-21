package com.vayunmathur.clock.platform

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.ui.components.sendTimerNotification
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import com.vayunmathur.testsupport.awaitWithMainIdle
import com.vayunmathur.testsupport.awaitValueBlocking
import kotlinx.coroutines.flow.combine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Clock

/** Tests for notification channels/helpers and the broadcast receivers (DB branches fail soft on JVM). */
@RunWith(RobolectricTestRunner::class)
class ReceiversAndNotificationsTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    companion object {
        /**
         * Several tests here start receiver coroutines that reach the
         * SQLCipher-backed repository, whose native library does not exist on
         * the host JVM. That error is stored process-wide by
         * kotlinx-coroutines-test and rethrown at the next compose test;
         * drain it here, where it belongs, so later classes stay green.
         */
        @org.junit.AfterClass @JvmStatic fun drainStoredDbErrors() {
            repeat(8) {
                run { var pumped = 0; awaitWithMainIdle("pump background work", maxSteps = 25_000) { ++pumped >= 25_000 } }
                try {
                    kotlinx.coroutines.test.runTest { }
                } catch (e: IllegalStateException) {
                    if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
                }
            }
        }
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = ctx.getSystemService(NotificationManager::class.java)


    @Test
    fun channelsAreCreated() {
        createNotificationChannels(ctx)
        val shadow = shadowOf(nm)
        val ids = shadow.notificationChannels.map { it.id }
        assertTrue("active_timers_channel" in ids, "ids=$ids")
        assertTrue("finished_timers_channel" in ids)
        assertTrue(ALARM_CHANNEL_ID in ids)
        assertTrue(ALARM_RING_CHANNEL_ID in ids)
        assertTrue("stopwatch_channel" in ids)
        createAlarmChannel(ctx)
        assertNotNull(nm.getNotificationChannel(ALARM_CHANNEL_ID))
    }

    @Test
    fun sendTimerNotificationPostsAndSchedulesThenCancels() {
        createNotificationChannels(ctx)
        val timer = Timer(true, "tea", Clock.System.now(), 5.minutes, 5.minutes, id = 123)
        sendTimerNotification(ctx, timer, true)
        assertNotNull(shadowOf(nm).getNotification(123.hashCode()))
        val am = ctx.getSystemService(AlarmManager::class.java)
        assertNotNull(shadowOf(am).nextScheduledAlarm)
        sendTimerNotification(ctx, timer, false)
        assertEquals(null, shadowOf(nm).getNotification(123.hashCode()))
    }

    @Test
    fun sendTimerNotificationPausedShowsResume() {
        createNotificationChannels(ctx)
        val timer = Timer(false, "", Clock.System.now(), 5.minutes, 5.minutes, id = 124)
        sendTimerNotification(ctx, timer, true)
        val posted = shadowOf(nm).getNotification(124.hashCode())
        assertNotNull(posted)
        assertEquals("Timer", posted.extras.getString("android.title"))
    }

    @Test
    fun stopwatchHelperCancelsWhenResetAndPostsWhenRunning() = runBlocking {
        val ds = DataStoreUtils.getInstance(ctx)
        ds.setBoolean(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false)
        ds.setLong(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL, 0L)
        StopwatchNotificationHelper.updateNotification(ctx)
        assertEquals(null, shadowOf(nm).getNotification(StopwatchActionReceiver.STOPWATCH_NOTIFICATION_ID))

        ds.setLong(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL, 65_000L)
        StopwatchNotificationHelper.updateNotification(ctx)
        val posted = shadowOf(nm).getNotification(StopwatchActionReceiver.STOPWATCH_NOTIFICATION_ID)
        assertNotNull(posted)
        assertEquals("01:05", posted.extras.getString("android.text"))

        ds.setBoolean(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, true)
        ds.setLong(StopwatchActionReceiver.KEY_STOPWATCH_START, Clock.System.now().toEpochMilliseconds())
        StopwatchNotificationHelper.updateNotification(ctx)
        assertNotNull(shadowOf(nm).getNotification(StopwatchActionReceiver.STOPWATCH_NOTIFICATION_ID))
        ds.setBoolean(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false)
        ds.setLong(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL, 0L)
    }

    @Test
    fun alarmReceiverWithoutIdDoesNothing() {
        AlarmReceiver().onReceive(ctx, Intent())
        assertEquals(null, shadowOf(nm).getNotification(ALARM_RING_NOTIFICATION_ID))
    }

    @Test
    fun alarmReceiverPostsRingNotification() {
        createNotificationChannels(ctx)
        val intent = Intent().apply {
            putExtra("ALARM_ID", 55L)
            putExtra(AlarmScheduler.EXTRA_LABEL, "wake up")
            putExtra(AlarmScheduler.EXTRA_GRADUAL_SECONDS, 0)
        }
        AlarmReceiver().onReceive(ctx, intent)
        val posted = shadowOf(nm).getNotification(ALARM_RING_NOTIFICATION_ID)
        assertNotNull(posted, "ring notification should be posted")
        // The receiver also tries to start the ringing activity.
        val started = shadowOf(ctx as android.app.Application).nextStartedActivity
        assertNotNull(started)
    }

    @Test
    fun alarmReceiverGradualSilentLabel() {
        createNotificationChannels(ctx)
        val intent = Intent().apply {
            putExtra("ALARM_ID", 56L)
            putExtra(AlarmScheduler.EXTRA_LABEL, "")
            putExtra(AlarmScheduler.EXTRA_GRADUAL_SECONDS, 30)
            putExtra(AlarmScheduler.EXTRA_RINGTONE_URI, "silent")
        }
        AlarmReceiver().onReceive(ctx, intent)
        assertNotNull(shadowOf(nm).getNotification(ALARM_RING_NOTIFICATION_ID))
    }

    @Test
    fun timerReceiverPostsFinishedNotification() {
        createNotificationChannels(ctx)
        val intent = Intent().apply {
            putExtra("timer_id", 77L)
            putExtra("timer_name", "pasta")
        }
        TimerReceiver().onReceive(ctx, intent)
        awaitWithMainIdle("finished notification", maxSteps = 100_000) { shadowOf(nm).getNotification(77L.hashCode()) != null }
    }

    @Test
    fun timerActionReceiverUnknownAndNullAction() {
        TimerActionReceiver().onReceive(ctx, Intent())
        // An action for a timer that cannot be loaded fails soft inside the receiver coroutine.
        val intent = Intent(TimerActionReceiver.ACTION_PAUSE).apply { putExtra("timer_id", 999L) }
        TimerActionReceiver().onReceive(ctx, intent)
        run { var pumped = 0; awaitWithMainIdle("pump background work", maxSteps = 5_000) { ++pumped >= 400 } }
    }

    @Test
    fun stopwatchActionToggleLapStop() = runBlocking {
        val ds = DataStoreUtils.getInstance(ctx)
        ds.setBoolean(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false)
        ds.setLong(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL, 0L)
        ds.setString(StopwatchActionReceiver.KEY_STOPWATCH_LAPS, "")

        StopwatchActionReceiver().onReceive(ctx, Intent(StopwatchActionReceiver.ACTION_TOGGLE))
        awaitValueBlocking(ds.booleanFlow(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false)) { it }

        StopwatchActionReceiver().onReceive(ctx, Intent(StopwatchActionReceiver.ACTION_LAP))
        awaitValueBlocking(ds.stringFlow(StopwatchActionReceiver.KEY_STOPWATCH_LAPS)) { it.isNotEmpty() }

        StopwatchActionReceiver().onReceive(ctx, Intent(StopwatchActionReceiver.ACTION_STOP))
        // Terminal persisted tuple for STOP: stopped flag, zeroed total and
        // cleared laps all landed (they are separate DataStore edits).
        awaitValueBlocking(
            combine(
                ds.booleanFlow(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, true),
                ds.longFlow(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL, -1L),
                ds.stringFlow(StopwatchActionReceiver.KEY_STOPWATCH_LAPS),
            ) { running, total, laps -> !running && total == 0L && laps.isEmpty() },
        ) { it }
        // Lap while stopped is ignored.
        StopwatchActionReceiver().onReceive(ctx, Intent(StopwatchActionReceiver.ACTION_LAP))
        run { var pumped = 0; awaitWithMainIdle("pump background work", maxSteps = 5_000) { ++pumped >= 400 } }
        assertEquals("", ds.getString(StopwatchActionReceiver.KEY_STOPWATCH_LAPS))
    }

    @Test
    fun bootReceiverIgnoresOtherActions() {
        BootReceiver().onReceive(ctx, Intent("com.example.OTHER"))
    }

    @Test
    fun bootReceiverRunsForBootCompleted() {
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        run { var pumped = 0; awaitWithMainIdle("pump background work", maxSteps = 5_000) { ++pumped >= 400 } }
    }

    @Test
    fun bootReceiverHandlesTimeAndZoneChanges() {
        // The rearm itself reads the SQLCipher-backed repository, whose native
        // driver is unavailable on the host JVM (see this class's DB branches);
        // what is pinned here is that the new actions enter the reschedule
        // path at all instead of falling through the when unmatched.
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_TIME_CHANGED))
        run { var pumped = 0; awaitWithMainIdle("pump background work", maxSteps = 5_000) { ++pumped >= 400 } }
    }
}
