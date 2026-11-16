package com.vayunmathur.cast.tv.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.protocol.Ping
import java.net.ServerSocket
import java.net.Socket
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ControlChannel round-trip, peer-close, and close semantics. */
@RunWith(RobolectricTestRunner::class)
class ControlChannelDeepTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun channelPair(): Pair<ControlChannel, ControlChannel> {
        val server = ServerSocket(0)
        var accepted: Socket? = null
        val thread = Thread { accepted = server.accept() }
        thread.start()
        val client = Socket("127.0.0.1", server.localPort)
        thread.join(5_000)
        server.close()
        return ControlChannel(accepted!!) to ControlChannel(client)
    }

    @Test fun roundTripAndAddresses() {
        val (tv, phone) = channelPair()
        assertTrue(tv.remoteAddress.isNotBlank())
        phone.send(Ping)
        assertTrue(tv.receive()?.message is Ping)
        tv.send(Ping)
        assertTrue(phone.receive()?.message is Ping)
        tv.close()
        phone.close()
    }

    @Test fun receiveReturnsNullAfterPeerLeaves() {
        val (tv, phone) = channelPair()
        phone.close()
        // The stream ends: receive reports null rather than throwing.
        assertNull(tv.receive())
        tv.close()
        tv.close() // closing twice is fine
    }

    @Test fun controllerStartStopCycles() {
        // listen() runs on the controller's scope; wait for the socket to appear.
        fun awaitSocket() {
            val deadline = System.currentTimeMillis() + 10_000
            while (ReceiverController.serverSocket == null && System.currentTimeMillis() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(25)
            }
        }
        ReceiverController.start(ctx)
        ReceiverController.start(ctx) // already running: a no-op second start
        awaitSocket()
        assertNotNull(ReceiverController.serverSocket)
        ReceiverController.stop()
        ReceiverController.stop() // stopping a stopped controller is fine
        ReceiverController.start(ctx)
        awaitSocket()
        assertNotNull(ReceiverController.serverSocket)
        ReceiverController.stop()
        // And once more with decoders installed, so listen() advertises real limits.
        FakeDecoders.install()
        try {
            ReceiverController.start(ctx)
            awaitSocket()
            assertNotNull(ReceiverController.serverSocket)
        } finally {
            ReceiverController.stop()
            FakeDecoders.reset()
        }
    }
}
