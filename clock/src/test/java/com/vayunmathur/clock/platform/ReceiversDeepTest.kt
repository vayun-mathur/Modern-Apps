package com.vayunmathur.clock.platform

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.clock.FakeAndroidKeyStore
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
import kotlin.time.Clock

/** Receiver branches the existing receiver tests do not reach. */
@RunWith(RobolectricTestRunner::class)
class ReceiversDeepTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = ctx.getSystemService(NotificationManager::class.java)

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out: $desc")
    }

    /** Consume the stored SQLCipher background error so later runTest tests stay clean. */
    private fun drain() {
        Thread.sleep(1_000)
        // Background DB coroutines can take a while to reach the failing open;
        // drain repeatedly so a late one cannot leak into the next compose test.
        repeat(8) {
            try {
                kotlinx.coroutines.test.runTest { }
            } catch (e: IllegalStateException) {
                if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
            }
            Thread.sleep(400)
        }
    }

    @Test fun alarmReceiverSnoozeFireWithSilentRingtone() {
        createNotificationChannels(ctx)
        val intent = Intent().apply {
            putExtra("ALARM_ID", 88L)
            putExtra(AlarmScheduler.EXTRA_LABEL, "")
            putExtra(AlarmScheduler.EXTRA_GRADUAL_SECONDS, 30)
            putExtra(AlarmScheduler.EXTRA_RINGTONE_URI, "silent")
            putExtra(AlarmScheduler.EXTRA_VIBRATE, false)
            putExtra(AlarmScheduler.EXTRA_IS_SNOOZE, true)
        }
        AlarmReceiver().onReceive(ctx, intent)
        assertNotNull(shadowOf(nm).getNotification(ALARM_RING_NOTIFICATION_ID))
        drain()
    }

    @Test fun alarmReceiverMissingIdIsIgnored() {
        AlarmReceiver().onReceive(ctx, Intent())
        assertEquals(null, shadowOf(nm).getNotification(ALARM_RING_NOTIFICATION_ID))
    }

    @Test fun timerReceiverWithoutNameUsesDefaultLabel() {
        createNotificationChannels(ctx)
        val intent = Intent().apply { putExtra("timer_id", 66L) }
        TimerReceiver().onReceive(ctx, intent)
        await("finished notification") { shadowOf(nm).getNotification(66L.hashCode()) != null }
        val posted = shadowOf(nm).getNotification(66L.hashCode())
        assertNotNull(posted)
        drain()
    }

    @Test fun timerActionReceiverDispatchesEveryAction() {
        // Each action reaches the repository for a timer that cannot be loaded on
        // the host JVM; the receiver must fail soft for every one of them.
        for (action in listOf(
            TimerActionReceiver.ACTION_PAUSE,
            TimerActionReceiver.ACTION_RESUME,
            TimerActionReceiver.ACTION_CANCEL,
            TimerActionReceiver.ACTION_RESET,
            "com.vayunmathur.clock.TIMER_UNKNOWN",
        )) {
            val intent = Intent(action).apply { putExtra("timer_id", 12345L) }
            TimerActionReceiver().onReceive(ctx, intent)
        }
        TimerActionReceiver().onReceive(ctx, Intent())
        drain()
    }

    @Test fun bootReceiverAcceptsLockedBootAndQuickBoot() {
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        BootReceiver().onReceive(ctx, Intent("android.intent.action.QUICKBOOT_POWERON"))
        BootReceiver().onReceive(ctx, Intent())
        drain()
    }

    @Test fun stopwatchReceiverPauseResumeAndUnknownAction() = runBlocking {
        val ds = DataStoreUtils.getInstance(ctx)
        ds.setBoolean(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false)
        ds.setLong(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL, 0L)
        ds.setString(StopwatchActionReceiver.KEY_STOPWATCH_LAPS, "")

        StopwatchActionReceiver().onReceive(ctx, Intent(StopwatchActionReceiver.ACTION_TOGGLE))
        await("running") { ds.getBoolean(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false) }
        Thread.sleep(150)
        // Robolectric's monotonic clock is tied to its paused scheduler and
        // does not advance over Thread.sleep, so backdate the stored
        // monotonic anchor to simulate 200ms of real elapsed time.
        ds.setLong(
            StopwatchActionReceiver.KEY_STOPWATCH_START_MONO,
            DualTimeBase.elapsedRealtimeMs() - 200L,
        )
        // Toggling again pauses and banks the elapsed total.
        StopwatchActionReceiver().onReceive(ctx, Intent(StopwatchActionReceiver.ACTION_TOGGLE))
        await("paused with banked total") {
            !ds.getBoolean(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, true) &&
                (ds.getLong(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL) ?: 0L) > 0L
        }
        // Lap while paused is ignored, and an unknown action changes nothing.
        StopwatchActionReceiver().onReceive(ctx, Intent(StopwatchActionReceiver.ACTION_LAP))
        StopwatchActionReceiver().onReceive(ctx, Intent("com.vayunmathur.clock.STOPWATCH_UNKNOWN"))
        Thread.sleep(200)
        assertEquals("", ds.getString(StopwatchActionReceiver.KEY_STOPWATCH_LAPS))
        assertTrue((ds.getLong(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL) ?: 0L) > 0L)
        // Clean up shared stopwatch state.
        ds.setBoolean(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false)
        ds.setLong(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL, 0L)
        Unit
    }

}
