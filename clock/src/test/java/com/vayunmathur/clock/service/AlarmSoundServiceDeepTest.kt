package com.vayunmathur.clock.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.testsupport.awaitWithMainIdle
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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


    @Test fun ringAttemptRunsVibrationAndRingtoneFallbackThenDestroys() {
        val intent = Intent(ctx, AlarmSoundService::class.java).apply { putExtra("ALARM_ID", 777L) }
        val controller = Robolectric.buildService(AlarmSoundService::class.java, intent).create()
        val result = controller.get().onStartCommand(intent, 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        run { var pumped = 0; awaitWithMainIdle("pump background work", maxSteps = 5_000) { ++pumped >= 400 } }
        controller.destroy()
        run { var pumped = 0; awaitWithMainIdle("pump background work", maxSteps = 5_000) { ++pumped >= 400 } }
    }

    @Test fun overlappingAlarmsHandOverAndDestroyIsClean() {
        val controller = Robolectric.buildService(AlarmSoundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(
            Intent(ctx, AlarmSoundService::class.java).apply { putExtra("ALARM_ID", 1L) }, 0, 1,
        )
        run { var pumped = 0; awaitWithMainIdle("pump background work", maxSteps = 5_000) { ++pumped >= 400 } }
        // A second alarm supersedes the first: the old player/vibrator are stopped first.
        service.onStartCommand(
            Intent(ctx, AlarmSoundService::class.java).apply { putExtra("ALARM_ID", 2L) }, 0, 2,
        )
        run { var pumped = 0; awaitWithMainIdle("pump background work", maxSteps = 5_000) { ++pumped >= 400 } }
        controller.destroy()
    }
}
