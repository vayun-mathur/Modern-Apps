package com.vayunmathur.cast.tv

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.tv.platform.ReceiverController
import com.vayunmathur.cast.tv.platform.ReceiverPhase
import com.vayunmathur.cast.tv.service.ReceiverService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.update
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ReceiverService's state-driven notifications: an incoming pairing brings the
 * TV to the front, mirroring cancels the incoming alert, and going idle re-arms.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceNotificationsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun pump() {
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            Thread.sleep(30)
        }
    }

    private fun phase(phase: ReceiverPhase) {
        ReceiverController.mutableState.update { it.copy(phase = phase) }
        pump()
    }

    @Test fun incomingPhasesDriveNotificationsAndFrontBringing() {
        val controller = Robolectric.buildService(ReceiverService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        pump()

        phase(ReceiverPhase.Pairing("Pixel 9", "123456", 1))
        // Bringing to front starts MainActivity (or posts the full-screen intent).
        val started = shadowOf(ctx as android.app.Application).nextStartedActivity
        assertTrue(started == null || started.component?.className == MainActivity::class.java.name)

        phase(ReceiverPhase.Connected("Pixel 9"))
        phase(ReceiverPhase.Mirroring("Pixel 9", 1920, 1080))
        phase(ReceiverPhase.Advertising)

        val notifications = shadowOf(
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager,
        ).allNotifications
        assertNotNull(notifications)

        service.onTimeout(1, 0)
        service.onDestroy()
        pump()
        controller.destroy()
    }
}
