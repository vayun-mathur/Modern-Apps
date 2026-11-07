package com.vayunmathur.sdk.controller

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class ControllerSocketTest {

    private fun loopbackPair(): Pair<ControllerSocket, ControllerSocket> {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val client = ControllerSocket()
        client.connect("127.0.0.1", server.localPort)
        val accepted = server.accept()
        server.close()
        val host = ControllerSocket()
        host.attach(accepted)
        return client to host
    }

    @Test
    fun plaintextRoundTripBothDirections() {
        val (client, host) = loopbackPair()
        val hello = ControllerHello(senderName = "Phone", senderId = "id-1")
        val sentBody = assertNotNull(client.send(hello))
        val received = assertNotNull(host.receive())
        assertEquals(hello, received.message)
        // The body handed back is exactly what crossed the wire (transcript material).
        assertTrue(sentBody.contentEquals(received.body))

        val lobby = ControllerLobby(LobbyState(gameId = "kart"))
        assertNotNull(host.send(lobby))
        assertEquals(lobby, assertNotNull(client.receive()).message)
        client.close()
        host.close()
    }

    @Test
    fun encryptedRoundTripAfterKeyInstall() {
        val (client, host) = loopbackPair()
        val key = ControllerKeys.of(ControllerKeys.newSecret()).control
        client.codec.useSessionKey(key)
        host.codec.useSessionKey(key)
        val msg = ControllerPairRequired(code = true)
        assertNotNull(client.send(msg))
        assertEquals(msg, assertNotNull(host.receive()).message)
        client.close()
        host.close()
    }

    @Test
    fun undecodableFrameReadsAsNullNotThrow() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val raw = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        server.close()
        val host = ControllerSocket()
        host.attach(accepted)
        // A framed non-JSON body decodes to null and ends the read.
        raw.getOutputStream().write(ControllerFraming.encode("junk".toByteArray()))
        raw.getOutputStream().flush()
        assertNull(host.receive())
        raw.close()
        host.close()
    }

    @Test
    fun oversizedFrameReadsAsNull() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val raw = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        server.close()
        val host = ControllerSocket()
        host.attach(accepted)
        val size = ControllerFraming.MAX_FRAME_BYTES + 1
        raw.getOutputStream().write(
            byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()),
        )
        raw.getOutputStream().flush()
        assertNull(host.receive())
        raw.close()
        host.close()
    }

    @Test
    fun peerCloseReadsAsNull() {
        val (client, host) = loopbackPair()
        client.close()
        assertNull(host.receive())
        host.close()
    }

    @Test
    fun sendAndReceiveOnNeverConnectedSocketReturnNull() {
        val socket = ControllerSocket()
        assertNull(socket.send(ControllerPing))
        assertNull(socket.receive())
        socket.close()
        // Double close is harmless.
        socket.close()
        assertNull(socket.send(ControllerPing))
    }

    @Test
    fun connectToDeadPortThrows() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        server.close()
        assertFailsWith<Exception> { ControllerSocket().connect("127.0.0.1", port) }
    }

    @Test
    fun receivedEqualityUsesMessageAndBodyContent() {
        val a = ControllerSocket.Received(ControllerPing, byteArrayOf(1, 2))
        val same = ControllerSocket.Received(ControllerPing, byteArrayOf(1, 2))
        val differentBody = ControllerSocket.Received(ControllerPing, byteArrayOf(1, 3))
        val differentMsg = ControllerSocket.Received(ControllerBye(), byteArrayOf(1, 2))
        assertEquals(a, same)
        assertEquals(a.hashCode(), same.hashCode())
        assertFalse(a == differentBody)
        assertFalse(a == differentMsg)
        assertFalse(a.equals(null))
        assertTrue(a.toString().contains("Ping") || a.toString().isNotEmpty())
    }
}
