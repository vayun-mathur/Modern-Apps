package com.vayunmathur.cast.tv

import android.content.Intent
import android.os.Looper
import com.vayunmathur.cast.tv.platform.MirrorActivity
import com.vayunmathur.cast.tv.platform.ReceiverController
import com.vayunmathur.cast.tv.platform.ReceiverPhase
import kotlinx.coroutines.flow.update
import com.vayunmathur.cast.tv.service.ReceiverService
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * The receiver shell on the host: MainActivity's create/start (which starts the
 * service and the phase collector) and ReceiverService's lifecycle.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiverShellTest {

    /**
     * Settle the main looper with bounded virtual time, never an unbounded idle.
     *
     * `shadowOf(...).idle()` returns only once the looper is empty, and a
     * composition hosting an infinite animation (an indeterminate spinner)
     * reposts a frame callback forever: the idle spins on Choreographer
     * frames until the test heap exhausts (previously ~290 s in
     * [mainActivityCreatesAndCollects], ending in a Recomposer
     * OutOfMemoryError). Stepping a bounded window of virtual time runs the
     * tasks and frames that are actually queued without betting on the
     * looper ever going quiet, and yields between steps so producers on
     * other threads get wall time without a sleep.
     */
    private fun pump() {
        repeat(SETTLE_STEPS) {
            shadowOf(Looper.getMainLooper()).idleFor(STEP_MS, TimeUnit.MILLISECONDS)
            Thread.yield()
        }
    }

    /** Pump the main looper until [condition] — a terminal signal — holds. */
    private fun pumpUntil(description: String, condition: () -> Boolean) {
        repeat(MAX_STEPS) {
            if (condition()) return
            shadowOf(Looper.getMainLooper()).idleFor(STEP_MS, TimeUnit.MILLISECONDS)
            Thread.yield()
        }
        check(condition()) { "terminal signal not reached: $description" }
    }

    @Test fun mainActivityCreatesAndCollects() {
        // Enter at Advertising rather than the default Starting phase.
        // Starting composes TvLoading's indeterminate spinner, whose frame
        // callbacks keep Robolectric's paused looper permanently non-idle, so
        // the unbounded idle inside ActivityController.visible() (and any
        // idle-based pump) never returns — see pump(). Advertising composes
        // the ready screen, which has no perpetual animation; the Starting
        // screen itself is covered by the compose tests.
        ReceiverController.mutableState.update {
            it.copy(phase = ReceiverPhase.Advertising)
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        pump()
        assertNotNull(controller.get())
        // A mirroring phase arriving launches the picture activity (recorded by
        // the shadow; the activity itself is a TV surface surface host). The
        // recorded intent is the collector's terminal signal: it is produced
        // by the lifecycle collector on the main looper, so pumping that
        // looper is what drives it, with no wall-clock bet.
        ReceiverController.mutableState.update {
            it.copy(phase = ReceiverPhase.Mirroring("Pixel", 1920, 1080))
        }
        pumpUntil("MirrorActivity launched for the mirroring phase") {
            shadowOf(controller.get().application).peekNextStartedActivity()
                ?.component?.className == MirrorActivity::class.java.name
        }
        ReceiverController.mutableState.update {
            it.copy(phase = ReceiverPhase.Advertising)
        }
        pump()
        controller.pause().stop().destroy()
        ReceiverController.forgetPlayback()
    }

    @Test fun receiverServiceLifecycle() {
        val controller = Robolectric.buildService(ReceiverService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        pump()
        // The stop action tears the controller down and stops the service.
        service.onStartCommand(Intent(ReceiverService.ACTION_STOP), 0, 2)
        service.onDestroy()
        pump()
        controller.destroy()
    }

    private companion object {
        /** Virtual milliseconds of looper time per pump step. */
        const val STEP_MS = 50L

        /** Steps in a plain settle: 200 ms of virtual looper time. */
        const val SETTLE_STEPS = 4

        /**
         * Failure bound for a terminal-signal wait, in looper steps (10 s of
         * virtual time). The step count is not the mechanism — each step runs
         * the queued main-looper work that produces the signal.
         */
        const val MAX_STEPS = 200
    }
}
