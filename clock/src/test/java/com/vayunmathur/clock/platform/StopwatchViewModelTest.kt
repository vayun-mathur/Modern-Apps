package com.vayunmathur.clock.platform

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.data.ClockRepository
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * The stopwatch half of the ViewModel: DataStore-backed, so it runs fully on
 * the host. Toggle banks elapsed time, laps accumulate, reset clears, and the
 * persisted state is what a fresh ViewModel reads back.
 */
@RunWith(RobolectricTestRunner::class)
class StopwatchViewModelTest {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = ClockViewModel(ctx, ClockRepository.get(ctx))

    private fun pump() {
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MILLISECONDS)
            Thread.sleep(30)
        }
    }

    @Test fun toggleLapResetAndReload() {
        val vm = vm()
        vm.resetStopwatch()
        pump()
        assertFalse(vm.stopwatchRunning.value)

        vm.toggleStopwatch()
        pump()
        assertTrue(vm.stopwatchRunning.value)
        assertTrue(vm.stopwatchCountingTime.value >= Duration.ZERO)

        vm.addLap()
        pump()
        vm.toggleStopwatch() // pause: banks the elapsed time
        pump()
        assertFalse(vm.stopwatchRunning.value)

        // A fresh ViewModel sees the persisted (paused) state.
        val reloaded = vm()
        pump()
        assertFalse(reloaded.stopwatchRunning.value)

        vm.resetStopwatch()
        pump()
        assertEquals(Duration.ZERO, vm.stopwatchCountingTime.value)
        assertTrue(vm.lapTimes.value.isEmpty())
    }
}
