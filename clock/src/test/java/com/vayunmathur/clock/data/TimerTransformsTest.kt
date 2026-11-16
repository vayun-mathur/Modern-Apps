package com.vayunmathur.clock.data

import com.vayunmathur.clock.platform.timerRemaining
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.clock.ui.components.sendTimerNotification
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Timer's own arithmetic: start/stop transforms and remaining-time clamping. */
@RunWith(RobolectricTestRunner::class)
class TimerTransformsTest {

    @Test fun startedAndStoppedRoundTrip() {
        val now = Clock.System.now()
        val paused = Timer(false, "tea", now, remainingLength = 3.minutes, totalLength = 5.minutes, id = 7)
        val running = paused.started()
        assertTrue(running.isRunning)
        assertEquals(3.minutes, running.remainingLength)
        // Stopping immediately banks (almost) the whole remainder.
        val stopped = running.stopped()
        assertFalse(stopped.isRunning)
        assertTrue(stopped.remainingLength <= 3.minutes)
        assertTrue(stopped.remainingLength > 3.minutes - 5.seconds)
    }

    @Test fun remainingClampsAtZeroForOverrunRunningTimer() {
        val past = Clock.System.now() - 10.minutes
        val overrun = Timer(true, "old", past, remainingLength = 5.minutes, totalLength = 5.minutes, id = 8)
        assertEquals(kotlin.time.Duration.ZERO, timerRemaining(overrun, Clock.System.now()))
    }

    @Test fun timerNotificationPostsAndSchedulesFinish() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val now = Clock.System.now()
        val running = Timer(true, "tea", now, remainingLength = 3.minutes, totalLength = 3.minutes, id = 21)
        sendTimerNotification(ctx, running, isStarting = true)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        kotlin.test.assertTrue(shadowOf(nm).allNotifications.isNotEmpty())
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        kotlin.test.assertTrue(shadowOf(am).scheduledAlarms.isNotEmpty())
        // Cancelling variant clears instead of posting.
        sendTimerNotification(ctx, running, isStarting = false)
    }

    @Test fun remainingForPausedTimerIsItsBankedLength() {
        val now = Clock.System.now()
        val paused = Timer(false, "tea", now, remainingLength = 2.minutes, totalLength = 5.minutes, id = 9)
        assertEquals(2.minutes, timerRemaining(paused, now + 3.minutes))
    }
}
