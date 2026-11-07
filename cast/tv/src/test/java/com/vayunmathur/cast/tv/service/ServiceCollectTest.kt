package com.vayunmathur.cast.tv.service

import android.app.Application
import android.content.Intent
import android.os.Looper
import com.vayunmathur.cast.tv.platform.ReceiverController
import com.vayunmathur.cast.tv.platform.ReceiverPhase
import kotlinx.coroutines.flow.update
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ServiceCollectTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun serviceObservesPhasesAndPostsNotifications() {
        val controller = Robolectric.buildService(ReceiverService::class.java).create()
        val service = controller.get()
        assertEquals(android.app.Service.START_STICKY, service.onStartCommand(Intent(), 0, 1))
        idle()
        // Drive the collector through idle -> incoming -> mirroring -> idle edges.
        ReceiverController.mutableState.update { it.copy(phase = ReceiverPhase.Pairing("Pixel", "123456", 3)) }; idle()
        ReceiverController.mutableState.update { it.copy(phase = ReceiverPhase.Connected("Pixel")) }; idle()
        ReceiverController.mutableState.update { it.copy(phase = ReceiverPhase.Mirroring("Pixel", 1920, 1080, appLabel = "YouPipe")) }; idle()
        ReceiverController.mutableState.update { it.copy(phase = ReceiverPhase.Failed(com.vayunmathur.cast.tv.platform.ReceiverFailure.StreamEnded)) }; idle()
        ReceiverController.mutableState.update { it.copy(phase = ReceiverPhase.Advertising) }; idle()
        service.onTimeout(1, 0)
        controller.destroy(); idle()
        ReceiverController.mutableState.update { it.copy(phase = ReceiverPhase.Starting) }
    }

    @Test fun serviceStartStopActions() {
        val controller = Robolectric.buildService(ReceiverService::class.java).create()
        val service = controller.get()
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(Intent(ReceiverService.ACTION_STOP), 0, 5))
        controller.destroy()
    }
}
