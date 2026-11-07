package com.vayunmathur.cast.network

import com.vayunmathur.cast.protocol.ControlCodec
import com.vayunmathur.cast.protocol.ControlFraming
import com.vayunmathur.cast.protocol.Hello
import com.vayunmathur.cast.protocol.Ping
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkTransportTest {

    @Test
    fun `udp send before open fails and receive is null`() {
        val transport = CastUdpTransport("127.0.0.1", 9)
        assertFalse(transport.send(byteArrayOf(1)))
        assertNull(transport.receive())
        assertFalse(transport.receiverGone)
        assertEquals(0, transport.sendFailures)
        transport.close()
        transport.close()
    }

    @Test
    fun `udp round trip to loopback peer`() {
        val peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        try {
            val transport = CastUdpTransport("127.0.0.1", peer.localPort)
            assertTrue(transport.open())
            assertTrue(transport.send(byteArrayOf(10, 20, 30)))
            val buf = ByteArray(64)
            val packet = DatagramPacket(buf, buf.size)
            peer.soTimeout = 5_000
            peer.receive(packet)
            assertContentEquals(byteArrayOf(10, 20, 30), packet.data.copyOf(packet.length))

            // Reply and read it back through the transport (poll, never blocks).
            peer.send(DatagramPacket(byteArrayOf(7, 8), 2, InetAddress.getByName("127.0.0.1"), packet.port))
            var got: ByteArray? = null
            val deadline = System.currentTimeMillis() + 5_000
            while (got == null && System.currentTimeMillis() < deadline) {
                got = transport.receive()
                if (got == null) Thread.sleep(10)
            }
            assertContentEquals(byteArrayOf(7, 8), assertNotNull(got))
            assertNull(transport.receive())
            transport.close()
            assertFalse(transport.send(byteArrayOf(1)))
        } finally {
            peer.close()
        }
    }

    @Test
    fun `udp open to unroutable port type still opens connected socket`() {
        // Connecting a UDP socket does not require a listener.
        val transport = CastUdpTransport("127.0.0.1", 1)
        transport.hexDump = true
        assertTrue(transport.open())
        transport.send(byteArrayOf(1, 2))
        transport.close()
    }

    @Test
    fun `udp open to an unresolvable host fails cleanly`() {
        assertFalse(CastUdpTransport("256.256.256.256", 9_999).open())
    }

    @Test
    fun `control socket refuses an oversize frame`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread {
            server.accept().use { socket ->
                val out = java.io.DataOutputStream(socket.outputStream)
                out.writeInt(2_000_000) // beyond ControlFraming.MAX_FRAME_BYTES
                out.flush()
                Thread.sleep(300)
            }
        }
        try {
            val socket = ControlSocket("127.0.0.1", server.localPort)
            socket.connect()
            assertNull(socket.receive())
            socket.close()
        } finally {
            server.close()
            serverThread.join(5_000)
        }
    }

    @Test
    fun `control socket round trip preserves message and body bytes`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread {
            server.accept().use { socket ->
                val input = DataInputStream(socket.inputStream.buffered())
                val output = DataOutputStream(socket.outputStream.buffered())
                val codec = ControlCodec()
                val body = ControlFraming.read(input)
                val decoded = codec.decode(assertNotNull(body))
                assertTrue(decoded is Hello)
                val replyBody = codec.encode(Ping)
                output.write(ControlFraming.encode(replyBody))
                output.flush()
            }
        }
        try {
            val socket = ControlSocket("127.0.0.1", server.localPort)
            assertNull(socket.send(Ping), "send before connect must be null")
            assertNull(socket.receive(), "receive before connect must be null")
            socket.connect()
            val sentBody = socket.send(Hello(senderName = "Phone", senderId = "id-1", paired = true))
            assertNotNull(sentBody)
            val received = socket.receive()
            assertNotNull(received)
            assertEquals(Ping, received.message)
            assertContentEquals(received.body, ControlCodec().encode(Ping))
            // Received equality is content based for the body.
            assertEquals(received, ControlSocket.Received(Ping, received.body.copyOf()))
            assertEquals(received.hashCode(), ControlSocket.Received(Ping, received.body.copyOf()).hashCode())
            socket.close()
            socket.close()
            assertNull(socket.receive())
        } finally {
            server.close()
            serverThread.join(5_000)
        }
    }

    @Test
    fun `control socket connect rejects an invalid port`() {
        var threw = false
        try {
            ControlSocket("127.0.0.1", -1).connect()
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        var ioThrew = false
        try {
            ControlSocket("256.256.256.256", 80).connect()
        } catch (e: java.io.IOException) {
            ioThrew = true
        } catch (e: IllegalArgumentException) {
            ioThrew = true
        }
        assertTrue(ioThrew)
    }

    @Test
    fun `control socket connect to closed port throws`() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val socket = ControlSocket("127.0.0.1", port)
        var threw = false
        try {
            socket.connect()
        } catch (e: java.io.IOException) {
            threw = true
        }
        assertTrue(threw)
    }
}
