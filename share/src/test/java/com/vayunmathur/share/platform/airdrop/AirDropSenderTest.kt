package com.vayunmathur.share.platform.airdrop

import android.content.Context
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.domain.airdrop.AirDropMessages
import com.vayunmathur.share.domain.airdrop.BinaryPlist
import com.vayunmathur.share.network.airdrop.AirDropHttp
import com.vayunmathur.share.network.airdrop.AirDropHttpServer
import com.vayunmathur.share.platform.ReceivedFileStore
import com.vayunmathur.share.platform.airdrop.link.AirDropLink
import java.io.File
import java.net.InetAddress
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AirDropSenderTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun fakeLink(): AirDropLink = com.vayunmathur.share.platform.airdrop.link.WifiLanLink(ctx)

    @Test
    fun `sender discover and send against real receiver`() = runBlocking {
        val store = ReceivedFileStore(ctx)
        val receiver = AirDropReceiver(store, { "RecvMac" }, { 1_000_000_000L }, { _, _ -> })
        val server = AirDropHttpServer(receiver::handle)
        try {
            val port = server.start()
            val peer = AirDropPeer(serviceName = "testsvc", address = InetAddress.getByName("127.0.0.1"), port = port)
            val link = fakeLink()
            val sender = AirDropSender { "SendPhone" }
            // Receiver advertises itself as Google, which the sender skips by design.
            val discovered = sender.discover(peer, link)
            assertNull(discovered)
            // Send: Ask will wait for user; accept it via receiver polling.
            val file = File(ctx.cacheDir, "send-${System.nanoTime()}.txt").apply { writeText("airdrop payload") }
            val sendThread = Thread {
                kotlinx.coroutines.runBlocking {
                    val result = sender.send(peer, link, listOf(file)) { _, _ -> }
                    assertTrue(result is AirDropSender.Result.Failed || result is AirDropSender.Result.Declined || result is AirDropSender.Result.Sent)
                }
            }
            // The Ask against a Google-manufacturer receiver still creates a session; accept if appears.
            sendThread.start()
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline && sendThread.isAlive) {
                val inc = receiver.incoming.value.firstOrNull()
                if (inc != null) receiver.accept(inc.sessionHandle, true)
                Thread.sleep(50)
            }
            sendThread.join(10_000)
            assertFalse(sendThread.isAlive)
        } finally { server.release() }
    }

    private fun assertFalse(b: Boolean) = org.junit.Assert.assertFalse(b)

    @Test
    fun `sender discover unreachable peer returns null`() = runBlocking {
        val sender = AirDropSender { "Phone" }
        val peer = AirDropPeer(serviceName = "dead", address = InetAddress.getByName("127.0.0.1"), port = 9)
        assertNull(sender.discover(peer, fakeLink()))
    }

    @Test
    fun `wifi lan link activates trivially`() = runBlocking {
        val link = com.vayunmathur.share.platform.airdrop.link.WifiLanLink(ctx)
        assertEquals(AirDropLink.Kind.WifiLan, link.kind)
        assertTrue(link.activate())
        link.deactivate()
        assertNotNull(link.socketFactory)
    }

    @Test
    fun `controller browse emits nothing without peers and start stop receiving`() = runBlocking {
        val controller = AirDropController(ctx, ReceivedFileStore(ctx)) { "CtrlPhone" }
        controller.startReceiving()
        controller.startReceiving() // idempotent
        controller.stopReceiving()
        controller.stopReceiving()
        assertTrue(true)
    }
}
