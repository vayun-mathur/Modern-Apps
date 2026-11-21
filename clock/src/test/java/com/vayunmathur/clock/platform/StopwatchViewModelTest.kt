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
import kotlinx.coroutines.flow.combine
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

    @Test fun toggleLapResetAndReload() {
        val vm = vm()
        vm.resetStopwatch()
        // Reset applies in memory at once; idle the looper so the
        // persisted-state collector cannot replay a stale snapshot over it.
        awaitWithMainIdle("reset settled") { !vm.stopwatchRunning.value && vm.lapTimes.value.isEmpty() }
        assertFalse(vm.stopwatchRunning.value)

        vm.toggleStopwatch()
        // Toggle flips the running flag synchronously; also wait for
        // the persisted terminal state, so the pause persist below
        // cannot be reordered ahead of this start persist.
        awaitValueBlocking(
            DataStoreUtils.getInstance(ctx)
                .booleanFlow(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false),
        ) { it }
        awaitWithMainIdle("running settled") { vm.stopwatchRunning.value }
        assertTrue(vm.stopwatchRunning.value)
        assertTrue(vm.stopwatchCountingTime.value >= Duration.ZERO)

        vm.addLap()
        awaitWithMainIdle("lap recorded") { vm.lapTimes.value.size == 1 }
        vm.toggleStopwatch() // pause: banks the elapsed time
        awaitValueBlocking(
            DataStoreUtils.getInstance(ctx)
                .booleanFlow(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false),
        ) { !it }
        awaitWithMainIdle("paused settled") { !vm.stopwatchRunning.value }
        assertFalse(vm.stopwatchRunning.value)

        // Persisted terminal state first, as a terminal signal: the
        // pause persist must have landed both the stopped flag and the
        // lap in DataStore before a fresh ViewModel is asked to reload
        // them. awaitValueBlocking suspends on the DataStore flows
        // (first{}), so it waits exactly as long as the IO takes. The
        // looper pump below cannot substitute for this: its steps are
        // idle+yield spins with no blocking, so on a loaded box (CI
        // runs the whole clock suite in one JVM at dd6, and every
        // earlier ViewModel's DataStore collector is still queued on
        // that JVM's main looper) the step budget burns out before
        // the persisted value reaches the fresh collector — the
        // 'reloaded persisted state' step-bound timeout. Awaiting the
        // running flag alone (above) was not enough: it fires on the
        // pause persist's first edit, before its lap edit lands.
        val ds = DataStoreUtils.getInstance(ctx)
        awaitValueBlocking(
            combine(
                ds.booleanFlow(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false),
                ds.stringFlow(StopwatchActionReceiver.KEY_STOPWATCH_LAPS),
            ) { isRunning, lapsStr ->
                !isRunning && lapsStr.split(",").count { it.isNotBlank() } == 1
            },
        ) { it }

        // A fresh ViewModel sees the persisted (paused) state: its load
        // collector must have replayed the persisted lap before this is
        // a meaningful read, so wait for that terminal state (a lap the
        // fresh VM did not add itself), not a fixed number of pumps.
        // The persisted value is already in DataStore (awaited above),
        // so this pump only replays it through the main looper.
        val reloaded = vm()
        awaitWithMainIdle("reloaded persisted state") {
            !reloaded.stopwatchRunning.value && reloaded.lapTimes.value.size == 1
        }
        assertFalse(reloaded.stopwatchRunning.value)

        vm.resetStopwatch()
        awaitWithMainIdle("reset after reload") {
            vm.stopwatchCountingTime.value == Duration.ZERO && vm.lapTimes.value.isEmpty()
        }
        assertEquals(Duration.ZERO, vm.stopwatchCountingTime.value)
        assertTrue(vm.lapTimes.value.isEmpty())
    }
}
