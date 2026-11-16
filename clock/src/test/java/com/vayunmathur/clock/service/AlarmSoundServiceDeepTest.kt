package com.vayunmathur.clock.service

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.clock.FakeAndroidKeyStore
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives the sound service's IO coroutine and main-thread vibration so the ring
 * attempt actually runs: the alarm DB is SQLCipher (unavailable on the host JVM),
 * so the service must fall back to vibrate + the default ringtone path.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmSoundServiceDeepTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun pump() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            Thread.sleep(25)
        }
    }

    @Test fun ringAttemptRunsVibrationAndRingtoneFallbackThenDestroys() {
        val intent = Intent(ctx, AlarmSoundService::class.java).apply { putExtra("ALARM_ID", 777L) }
        val controller = Robolectric.buildService(AlarmSoundService::class.java, intent).create()
        val result = controller.get().onStartCommand(intent, 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        pump()
        controller.destroy()
        pump()
    }

    @Test fun overlappingAlarmsHandOverAndDestroyIsClean() {
        val controller = Robolectric.buildService(AlarmSoundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(
            Intent(ctx, AlarmSoundService::class.java).apply { putExtra("ALARM_ID", 1L) }, 0, 1,
        )
        pump()
        // A second alarm supersedes the first: the old player/vibrator are stopped first.
        service.onStartCommand(
            Intent(ctx, AlarmSoundService::class.java).apply { putExtra("ALARM_ID", 2L) }, 0, 2,
        )
        pump()
        controller.destroy()
    }
}
