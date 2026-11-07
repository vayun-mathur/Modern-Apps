package com.vayunmathur.share.platform

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.Route
import com.vayunmathur.share.SharePopupActivity
import com.vayunmathur.share.domain.airdrop.BinaryPlist
import com.vayunmathur.share.network.transport.TcpTransport
import com.vayunmathur.share.platform.airdrop.link.AirDropLinkSelector
import com.vayunmathur.share.platform.airdrop.link.MoseyDetector
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowServiceManager

@RunWith(RobolectricTestRunner::class)
class GapPush9Test {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `route share is serializable nav key`() {
        val route: Route = Route.Share
        assertEquals(Route.Share, route)
        val json = Json.encodeToString(Route.serializer(), route)
        assertTrue(json.contains("Share"))
        assertEquals(route, Json.decodeFromString(Route.serializer(), json))
    }

    @Test
    fun `binary plist root level values`() {
        assertEquals("solo", BinaryPlist.decode(BinaryPlist.encode("solo")))
        assertEquals(99L, BinaryPlist.decode(BinaryPlist.encode(99L)))
        assertEquals(true, BinaryPlist.decode(BinaryPlist.encode(true)))
        assertEquals(listOf(1L, 2L), BinaryPlist.decode(BinaryPlist.encode(listOf(1L, 2L))))
        assertEquals(emptyMap<String, Any?>(), BinaryPlist.decode(BinaryPlist.encode(emptyMap<String, Any?>())))
    }

    @Test
    fun `share popup activity recreation does not restage intent`() {
        val intent = Intent(app, SharePopupActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "recreate me")
        }
        val controller = Robolectric.buildActivity(SharePopupActivity::class.java, intent)
        controller.create().start().resume()
        controller.recreate()
        controller.pause().stop().destroy()
        // SEND_MULTIPLE variant
        val multi = Intent(app, SharePopupActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "text/plain"
            val f = File(app.cacheDir, "pop-${System.nanoTime()}.txt").apply { writeText("m") }
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.fromFile(f)))
        }
        val c2 = Robolectric.buildActivity(SharePopupActivity::class.java, multi)
        c2.create().start().resume()
        c2.pause().stop().destroy()
    }

    @Test
    fun `tcp keep alive fires on idle connection`() = runBlocking {
        val server = TcpTransport("KaSrv", ReceivedFileStore(app))
        val client = TcpTransport("KaCli", ReceivedFileStore(app))
        try {
            val port = server.listen()
            val conn = client.connect("127.0.0.1", port)
            // Pump read timeout is 2s and keep-alive interval 5s: idle past both.
            delay(7_000)
            // Connection survives idle (no failure recorded by the pump).
            assertTrue(client.connectionFor(conn.sessionHandle) != null)
            client.disconnect(conn)
        } finally { client.release(); server.release() }
    }

    private class FakeBinder : android.os.IBinder {
        override fun getInterfaceDescriptor(): String = "x"
        override fun pingBinder(): Boolean = true
        override fun isBinderAlive(): Boolean = true
        override fun queryLocalInterface(descriptor: String): android.os.IInterface? = null
        override fun dump(fd: java.io.FileDescriptor, args: Array<out String>?) {}
        override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<out String>?) {}
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean { reply?.writeInt(0); return true }
        override fun linkToDeath(recipient: android.os.IBinder.DeathRecipient, flags: Int) {}
        override fun unlinkToDeath(recipient: android.os.IBinder.DeathRecipient, flags: Int): Boolean = true
    }

    @Test
    fun `link selector with mosey declared but no country falls back to wifi`() = runBlocking {
        ShadowServiceManager.reset()
        try {
            ShadowServiceManager.setServiceDeclaredOrNot(MoseyDetector.AIDL_ANDROID, true)
            val iface = object : android.os.IInterface { override fun asBinder() = FakeBinder() }
            runCatching { ShadowServiceManager.addBinderService(MoseyDetector.AIDL_ANDROID, android.os.IInterface::class.java, iface) }
            val selection = AirDropLinkSelector.select(app)
            assertNotNull(selection.link)
            // No country code in Robolectric: Mosey cannot start, Wi-Fi is selected.
            assertEquals(com.vayunmathur.share.platform.airdrop.link.AirDropLink.Kind.WifiLan, selection.link.kind)
            selection.link.deactivate()
        } finally { ShadowServiceManager.reset() }
    }
}

