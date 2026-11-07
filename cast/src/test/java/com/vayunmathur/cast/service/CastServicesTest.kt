package com.vayunmathur.cast.service

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.domain.ClientState
import com.vayunmathur.cast.platform.CastController
import com.vayunmathur.sdk.cast.CastContract
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CastServicesTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() = runBlocking { CastController.teardown() }
    @After fun cleanup() = runBlocking { CastController.teardown() }

    @Test
    fun `cast service handles start stop and mirroring stop commands`() {
        val controller = Robolectric.buildService(CastService::class.java)
        val service = controller.create().get()
        assertNull(service.onBind(null))
        assertEquals(
            android.app.Service.START_NOT_STICKY,
            service.onStartCommand(Intent(app, CastService::class.java), 0, 1),
        )
        // Second start does not double-register the collector.
        service.onStartCommand(null, 0, 2)
        service.onStartCommand(
            Intent(app, CastService::class.java).setAction("com.vayunmathur.cast.action.STOP_MIRRORING"), 0, 3,
        )
        service.onStartCommand(
            Intent(app, CastService::class.java).setAction("com.vayunmathur.cast.action.START_MIRRORING"), 0, 4,
        )
        service.onTimeout(1, 0)
        service.onStartCommand(
            Intent(app, CastService::class.java).setAction(CastService.ACTION_STOP), 0, 5,
        )
        controller.destroy()
    }

    @Test
    fun `cast service notification tracks controller state`() {
        val controller = Robolectric.buildService(CastService::class.java)
        val service = controller.create().get()
        CastController.deviceMutable.value =
            CastDevice(id = "tv1", friendlyName = "Living Room TV", host = "127.0.0.1", port = 9)
        CastController.sessionStateMutable.value = ClientState(phase = ClientPhase.Paired)
        service.onStartCommand(null, 0, 1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val notifications = Shadows.shadowOf(
            app.getSystemService(NotificationManager::class.java),
        ).allNotifications
        assertTrue(notifications.isNotEmpty(), "session notification should be posted")
        controller.destroy()
    }

    @Test
    fun `cast service companion launches and stops`() {
        CastService.start(app)
        assertNotNull(Shadows.shadowOf(app).nextStartedService)
        CastService.startMirroring(app, -1, Intent())
        Shadows.shadowOf(app).nextStartedService
        CastService.stopMirroring(app)
        Shadows.shadowOf(app).nextStartedService
        CastService.stop(app)
    }

    @Test
    fun `content service drops messages with no session and answers open with ended`() {
        val controller = Robolectric.buildService(ContentCastService::class.java)
        val service = controller.create().get()
        val binder = assertNotNull(service.onBind(null))
        val incoming = Messenger(binder)

        val received = mutableListOf<Message>()
        val replyTo = Messenger(Handler(Looper.getMainLooper()) { msg ->
            received += Message.obtain(msg)
            true
        })

        // Dropped-with-no-session messages of every gated kind.
        for (what in listOf(
            CastContract.MSG_PLAYBACK_STATE, CastContract.MSG_PLAY_MEDIA,
            CastContract.MSG_SEND_PLAYBACK_COMMAND, CastContract.MSG_SET_NOW_PLAYING,
            CastContract.MSG_CLOSE_SESSION, 999,
        )) {
            incoming.send(Message.obtain(null, what).apply { data = Bundle() })
        }
        incoming.send(
            Message.obtain(null, CastContract.MSG_RESOURCE_RESPONSE).apply { data = Bundle() },
        )
        incoming.send(
            Message.obtain(null, CastContract.MSG_RESOURCE_COMPLETE).apply { data = Bundle() },
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Open with nowhere paired: the service answers SESSION_ENDED/NO_SESSION.
        incoming.send(Message.obtain(null, CastContract.MSG_OPEN_SESSION).apply {
            replyTo?.let { this.replyTo = it }
            data = Bundle().apply {
                putInt(CastContract.KEY_WIDTH, 640)
                putInt(CastContract.KEY_HEIGHT, 480)
            }
        })
        // Open without replyTo is refused quietly.
        incoming.send(Message.obtain(null, CastContract.MSG_OPEN_SESSION).apply { data = Bundle() })

        val deadline = System.currentTimeMillis() + 8_000
        while (received.none { it.what == CastContract.MSG_SESSION_ENDED } &&
            System.currentTimeMillis() < deadline
        ) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        val ended = received.first { it.what == CastContract.MSG_SESSION_ENDED }
        assertEquals(
            CastContract.REASON_NO_SESSION,
            ended.data.getInt(CastContract.KEY_END_REASON),
        )
        assertEquals(false, service.onUnbind(null))
        controller.destroy()
    }

    @Test
    fun `remote display service refuses foreign actions`() {
        val controller = Robolectric.buildService(CastRemoteDisplayService::class.java)
        val service = controller.create().get()
        assertNull(service.onBind(Intent("com.example.WRONG")))
        assertNull(service.onBind(null))
        controller.destroy()
    }
}
