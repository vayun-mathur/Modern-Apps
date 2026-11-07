package com.vayunmathur.cast.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.domain.ClientState
import com.vayunmathur.cast.platform.CastController
import com.vayunmathur.cast.platform.MirrorPhase
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows

/** The foreground notification's text for every session phase. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CastServiceStatusTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() = runBlocking { CastController.teardown() }
    @After fun cleanup() = runBlocking { CastController.teardown() }

    private fun latestText(): String? {
        val nm = app.getSystemService(NotificationManager::class.java)
        val all = Shadows.shadowOf(nm).allNotifications
        val last = all.lastOrNull() ?: return null
        return last.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    }

    private fun pump() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(60)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `notification text follows session and mirror phases`() {
        CastController.deviceMutable.value =
            CastDevice(id = "tv1", friendlyName = "Living Room TV", host = "127.0.0.1", port = 9)
        val controller = Robolectric.buildService(CastService::class.java)
        val service = controller.create().get()
        service.onStartCommand(null, 0, 1)

        val cases = listOf(
            (ClientPhase.Connecting to MirrorPhase.Idle) to R.string.cast_notification_text_connecting,
            (ClientPhase.AwaitingCode to MirrorPhase.Idle) to R.string.cast_notification_text_pairing,
            (ClientPhase.Paired to MirrorPhase.Idle) to R.string.cast_status_ready,
            (ClientPhase.Failed to MirrorPhase.Idle) to R.string.cast_notification_text_failed,
            (ClientPhase.Streaming to MirrorPhase.Mirroring) to R.string.cast_status_mirroring,
            (ClientPhase.Streaming to MirrorPhase.Negotiating) to R.string.cast_notification_text_starting,
            (ClientPhase.Streaming to MirrorPhase.Failed) to R.string.cast_notification_text_failed,
            (ClientPhase.Idle to MirrorPhase.Idle) to R.string.cast_status_ready,
        )
        for ((phases, expectedRes) in cases) {
            CastController.sessionStateMutable.value = ClientState(phase = phases.first)
            CastController.mirrorPhaseMutable.value = phases.second
            pump()
            assertEquals(app.getString(expectedRes), latestText(), "for $phases")
        }
        controller.destroy()
    }
}
