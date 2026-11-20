package com.vayunmathur.clock.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.data.ClockRepository
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.testsupport.awaitValueBlocking
import com.vayunmathur.testsupport.awaitWithMainIdle
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

    /**
     * Pumps Robolectric's paused main looper (where viewModelScope and
     * the stopwatch's DataStore collector resume) until [cond] — a
     * terminal StateFlow state — holds. Step-bounded and sleep-free:
     * each step is a looper idle, not a wall-clock bet. Replaces a fixed
     * `repeat(6) { idle; idleFor(30ms); Thread.sleep(30) }` pump that
     * both wasted real time and still raced the persisted reload.
     */
    private fun pumpUntil(desc: String, cond: () -> Boolean) =
        awaitWithMainIdle(desc) { cond() }

    @Test fun toggleLapResetAndReload() {
        val vm = vm()
        vm.resetStopwatch()
        // Reset applies in memory at once; idle the looper so the
        // persisted-state collector cannot replay a stale snapshot over it.
        pumpUntil("reset settled") { !vm.stopwatchRunning.value && vm.lapTimes.value.isEmpty() }
        assertFalse(vm.stopwatchRunning.value)

        vm.toggleStopwatch()
        // Toggle flips the running flag synchronously; also wait for
        // the persisted terminal state, so the pause persist below
        // cannot be reordered ahead of this start persist.
        awaitValueBlocking(
            DataStoreUtils.getInstance(ctx)
                .booleanFlow(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false),
        ) { it }
        pumpUntil("running settled") { vm.stopwatchRunning.value }
        assertTrue(vm.stopwatchRunning.value)
        assertTrue(vm.stopwatchCountingTime.value >= Duration.ZERO)

        vm.addLap()
        pumpUntil("lap recorded") { vm.lapTimes.value.size == 1 }
        vm.toggleStopwatch() // pause: banks the elapsed time
        awaitValueBlocking(
            DataStoreUtils.getInstance(ctx)
                .booleanFlow(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false),
        ) { !it }
        pumpUntil("paused settled") { !vm.stopwatchRunning.value }
        assertFalse(vm.stopwatchRunning.value)

        // A fresh ViewModel sees the persisted (paused) state: its load
        // collector must have replayed the persisted lap before this is
        // a meaningful read, so wait for that terminal state (a lap the
        // fresh VM did not add itself), not a fixed number of pumps.
        val reloaded = vm()
        pumpUntil("reloaded persisted state") {
            !reloaded.stopwatchRunning.value && reloaded.lapTimes.value.size == 1
        }
        assertFalse(reloaded.stopwatchRunning.value)

        vm.resetStopwatch()
        pumpUntil("reset after reload") {
            vm.stopwatchCountingTime.value == Duration.ZERO && vm.lapTimes.value.isEmpty()
        }
        assertEquals(Duration.ZERO, vm.stopwatchCountingTime.value)
        assertTrue(vm.lapTimes.value.isEmpty())
    }
}
