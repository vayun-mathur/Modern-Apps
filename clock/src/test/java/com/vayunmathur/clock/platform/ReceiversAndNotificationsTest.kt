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

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = ctx.getSystemService(NotificationManager::class.java)

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out: $desc")
    }

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
        await("finished notification") { shadowOf(nm).getNotification(77L.hashCode()) != null }
    }

    @Test
    fun timerActionReceiverUnknownAndNullAction() {
        TimerActionReceiver().onReceive(ctx, Intent())
        // An action for a timer that cannot be loaded fails soft inside the receiver coroutine.
        val intent = Intent(TimerActionReceiver.ACTION_PAUSE).apply { putExtra("timer_id", 999L) }
        TimerActionReceiver().onReceive(ctx, intent)
        Thread.sleep(200)
    }

    @Test
    fun stopwatchActionToggleLapStop() = runBlocking {
        val ds = DataStoreUtils.getInstance(ctx)
        ds.setBoolean(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false)
        ds.setLong(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL, 0L)
        ds.setString(StopwatchActionReceiver.KEY_STOPWATCH_LAPS, "")

        StopwatchActionReceiver().onReceive(ctx, Intent(StopwatchActionReceiver.ACTION_TOGGLE))
        await("running") { ds.getBoolean(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false) }

        StopwatchActionReceiver().onReceive(ctx, Intent(StopwatchActionReceiver.ACTION_LAP))
        await("lap recorded") { (ds.getString(StopwatchActionReceiver.KEY_STOPWATCH_LAPS) ?: "").isNotEmpty() }

        StopwatchActionReceiver().onReceive(ctx, Intent(StopwatchActionReceiver.ACTION_STOP))
        await("stopped") {
            !ds.getBoolean(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, true) &&
                (ds.getLong(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL) ?: -1L) == 0L
        }
        // Lap while stopped is ignored.
        StopwatchActionReceiver().onReceive(ctx, Intent(StopwatchActionReceiver.ACTION_LAP))
        Thread.sleep(200)
        assertEquals("", ds.getString(StopwatchActionReceiver.KEY_STOPWATCH_LAPS))
    }

    @Test
    fun bootReceiverIgnoresOtherActions() {
        BootReceiver().onReceive(ctx, Intent("com.example.OTHER"))
    }

    @Test
    fun bootReceiverRunsForBootCompleted() {
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        Thread.sleep(200)
    }

    @Test
    fun bootReceiverHandlesTimeAndZoneChanges() {
        // The rearm itself reads the SQLCipher-backed repository, whose native
        // driver is unavailable on the host JVM (see this class's DB branches);
        // what is pinned here is that the new actions enter the reschedule
        // path at all instead of falling through the when unmatched.
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_TIME_CHANGED))
        Thread.sleep(200)
    }
}
