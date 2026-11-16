package com.vayunmathur.cast.tv

import android.content.Intent
import android.os.Looper
import com.vayunmathur.cast.tv.platform.ReceiverController
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

    private fun pump() {
        repeat(8) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            Thread.sleep(30)
        }
    }

    @Test fun mainActivityCreatesAndCollects() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        pump()
        assertNotNull(controller.get())
        // A mirroring phase arriving launches the picture activity (recorded by
        // the shadow; the activity itself is a TV surface surface host).
        ReceiverController.mutableState.update {
            it.copy(phase = com.vayunmathur.cast.tv.platform.ReceiverPhase.Mirroring("Pixel", 1920, 1080))
        }
        pump()
        ReceiverController.mutableState.update {
            it.copy(phase = com.vayunmathur.cast.tv.platform.ReceiverPhase.Advertising)
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
}
