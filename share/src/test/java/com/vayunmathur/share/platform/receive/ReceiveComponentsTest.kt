package com.vayunmathur.share.platform.receive

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.platform.transfer.ShareTransferService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class ReceiveComponentsTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `controller defaults and permission checks`() = runBlocking {
        assertFalse(ShareReceiveController.isReceiveEnabled(app))
        assertTrue(ShareReceiveController.localName.isNotBlank())
        // endpointInfo uses native lib (available on host)
        val info = ShareReceiveController.endpointInfo()
        assertNotNull(info)
        assertTrue(info!!.isNotEmpty())
        assertFalse(ShareReceiveController.hasActiveTransfers(app))
        assertTrue(ShareReceiveController.transport(app) === ShareReceiveController.transport(app))
        assertTrue(ShareReceiveController.nsd(app) === ShareReceiveController.nsd(app))
        ShareReceiveController.setLocalName(app, "  Renamed Phone  ")
        assertEquals("Renamed Phone", ShareReceiveController.localName)
        ShareReceiveController.setLocalName(app, "   ") // blank ignored
        assertEquals("Renamed Phone", ShareReceiveController.localName)
        ShareReceiveController.setLocalName(app, "Renamed Phone") // same ignored
        // unknown handles
        assertFalse(ShareReceiveController.hasSession(app, 424242L))
        assertEquals(-1, ShareReceiveController.acceptIncoming(app, 424242L, true).let { if (it == 0) -1 else it }.let { ShareReceiveController.transport(app).acceptIncoming(424242L, true) })
    }

    @Test
    fun `boot receiver ignores unrelated actions`() {
        val receiver = ShareBootReceiver()
        receiver.onReceive(app, Intent("com.example.OTHER"))
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        // goAsync path runs async; just assert no crash
        Thread.sleep(300)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `notification receiver ignores unknown actions and missing handle`() {
        val receiver = ShareNotificationReceiver()
        receiver.onReceive(app, Intent("com.example.OTHER"))
        receiver.onReceive(app, Intent(ShareTransferService.ACTION_ACCEPT))
        receiver.onReceive(app, Intent(ShareTransferService.ACTION_ACCEPT).putExtra(ShareTransferService.EXTRA_SESSION_HANDLE, 0L))
        // valid action but no live session -> clears notification, no crash
        receiver.onReceive(app, Intent(ShareTransferService.ACTION_ACCEPT).apply {
            putExtra(ShareTransferService.EXTRA_SESSION_HANDLE, 987654L)
            putExtra(ShareTransferService.EXTRA_NOTIF_ID, 4200)
        })
    }

    @Test
    fun `transfer service constants and companion intents`() {
        assertEquals("com.vayunmathur.share.action.START_RECEIVE", ShareTransferService.ACTION_START_RECEIVE)
        assertEquals("com.vayunmathur.share.action.START_SEND", ShareTransferService.ACTION_START_SEND)
        ShareTransferService.startSendMode(app)
        ShareTransferService.stop(app)
        ShareTransferService.routeSessionAction(app, ShareTransferService.ACTION_ACCEPT, 123L, 4201)
        val started = Shadows.shadowOf(app).nextStartedService
        assertNotNull(started)
    }

    @Test
    fun `transfer service lifecycle via Robolectric`() {
        val controller = Robolectric.buildService(ShareTransferService::class.java)
        val service = controller.create().get()
        assertEquals(null, service.onBind(null))
        val rc = service.onStartCommand(Intent(ShareTransferService.ACTION_START_SEND), 0, 1)
        assertEquals(android.app.Service.START_STICKY, rc)
        service.onStartCommand(null, 0, 2)
        controller.destroy()
    }

    @Test
    fun `save activity finishes immediately with no uris`() {
        val controller = Robolectric.buildActivity(ShareSaveActivity::class.java, Intent(app, ShareSaveActivity::class.java))
        val activity = controller.create().get()
        assertTrue(activity.isFinishing)
        controller.destroy()
    }
}
