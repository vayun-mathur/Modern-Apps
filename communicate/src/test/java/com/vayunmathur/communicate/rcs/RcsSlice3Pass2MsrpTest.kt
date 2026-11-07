package com.vayunmathur.communicate.rcs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.MsrpSetup
import com.vayunmathur.communicate.data.rcs.RcsMsrp
import com.vayunmathur.communicate.data.rcs.RcsMsrpFraming
import com.vayunmathur.communicate.data.rcs.RcsMsrpListen
import com.vayunmathur.communicate.data.rcs.RcsSession
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-3 pass 2: the MSRP socket layer over real loopback TCP — one-shot
 * sends with FIFO acks, persistent connections (chunked SEND, inbound SEND
 * delivery + auto-200, REPORT/AUTH handling, keepalives), and the
 * accepted-socket wrapper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RcsSlice3Pass2ConnectivityShadow::class])
class RcsSlice3Pass2MsrpTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun session(port: Int, setup: MsrpSetup? = MsrpSetup.PASSIVE) = RcsSession(
        dialogId = "pass2-d", callId = "pass2-c", localTag = "lt", remoteTag = "rt",
        remoteUri = "sip:peer@rcs", conversationId = "conv-pass2",
        msrpLocalPath = "msrp://127.0.0.1:$port/self;tcp",
        msrpRemotePath = "msrp://127.0.0.1:$port/peer;tcp",
        msrpSetup = setup, confirmed = true,
    )

    /** A loopback peer thread helper: runs [block] against one accepted socket. */
    private fun serveOnce(server: ServerSocket, block: (Socket) -> Unit): CountDownLatch {
        val done = CountDownLatch(1)
        Thread {
            runCatching { server.accept().use(block) }
            done.countDown()
        }.apply { isDaemon = true; start() }
        return done
    }

    private fun readHead(input: java.io.BufferedReader): List<String> {
        val lines = mutableListOf<String>()
        var line = input.readLine() ?: return lines
        var guard = 0
        while (line.isNotEmpty() && guard++ < 32) {
            lines.add(line)
            line = input.readLine() ?: break
        }
        return lines
    }

    private fun readToMarker(input: java.io.BufferedReader, txid: String): String {
        val sb = StringBuilder()
        while (true) {
            val l = input.readLine() ?: break
            if (l.startsWith("-------$txid")) break
            sb.append(l).append("\r\n")
        }
        return sb.toString()
    }

    private fun respondOk(socket: Socket, txid: String, toPath: String) {
        val out = socket.getOutputStream()
        out.write(RcsMsrpFraming.buildErrorResponse(toPath, txid, 200, "OK"))
        out.flush()
    }

    // --- one-shot send ---

    @Test
    fun oneShotSendDeliversChunksAndMatchesAcks() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val received = AtomicReference<String>()
        val done = serveOnce(server) { s ->
            s.soTimeout = 15_000
            val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
            val all = StringBuilder()
            // Payload below is > 4096 bytes, so three chunks arrive; ack each.
            repeat(3) {
                val head = readHead(input)
                val txid = head.firstOrNull()?.split(" ")?.getOrNull(1).orEmpty()
                val body = readToMarker(input, txid)
                all.append(head.joinToString("\n")).append("\n").append(body)
                val toPath = head.firstOrNull { it.startsWith("From-Path:", true) }
                    ?.substringAfter(":")?.trim().orEmpty()
                respondOk(s, txid, toPath)
            }
            received.set(all.toString())
        }
        // ASCII, newline-free payload: the peer reads it line-wise.
        val payload = "msrp-one-shot-payload-".repeat(230).toByteArray(Charsets.UTF_8)
        val ok = RcsMsrp.send(session(port), context, payload)
        assertTrue(ok, "one-shot send was not acked")
        assertTrue(done.await(15, TimeUnit.SECONDS))
        val wire = received.get()
        assertTrue(wire.contains("MSRP "), wire.take(200))
        assertTrue(wire.contains("Byte-Range: 1-2048/5060"), wire.take(400))
        assertTrue(wire.contains("Byte-Range: 2049-4096/5060"))
        assertTrue(wire.contains("Byte-Range: 4097-5060/5060"))
        assertTrue(wire.contains("Content-Type: message/cpim"))
        server.close()
    }

    @Test
    fun oneShotSendFailureModes() = runBlocking {
        // Empty payload: nothing to send.
        assertFalse(RcsMsrp.send(session(9), context, ByteArray(0)))
        // No remote path.
        assertFalse(RcsMsrp.send(session(9).copy(msrpRemotePath = null), context, byteArrayOf(1)))
        // Unparseable path.
        assertFalse(
            RcsMsrp.send(session(9).copy(msrpRemotePath = "not-a-path"), context, byteArrayOf(1)),
        )
        // Closed port: connection refused.
        assertFalse(RcsMsrp.send(session(1), context, byteArrayOf(1, 2, 3)))
        // Secure session without a known peer fingerprint: refused (no downgrade in one-shot send).
        assertFalse(
            RcsMsrp.send(session(1).copy(msrpSecure = true), context, byteArrayOf(1, 2, 3)),
        )
    }

    @Test
    fun oneShotSendNonSuccessAckFails() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val done = serveOnce(server) { s ->
            s.soTimeout = 15_000
            val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
            val head = readHead(input)
            val txid = head.firstOrNull()?.split(" ")?.getOrNull(1).orEmpty()
            readToMarker(input, txid)
            val out = s.getOutputStream()
            out.write(RcsMsrpFraming.buildErrorResponse("", txid, 413, "Message Too Large"))
            out.flush()
        }
        assertFalse(RcsMsrp.send(session(port), context, "hello".toByteArray()))
        assertTrue(done.await(15, TimeUnit.SECONDS))
        server.close()
    }

    // --- connect() guards ---

    @Test
    fun connectGuards() = runBlocking {
        assertNull(RcsMsrp.connect(session(9).copy(msrpRemotePath = null), context) { _, _ -> })
        // Peer active + no listen socket: cannot establish.
        assertNull(RcsMsrp.connect(session(9, MsrpSetup.ACTIVE), context) { _, _ -> })
        // Unreachable peer.
        assertNull(RcsMsrp.connect(session(1), context) { _, _ -> })
    }

    // --- persistent connection, both directions ---

    @Test
    fun persistentConnectionBothDirections() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val chunksSeen = CopyOnWriteArrayList<String>()
        val peerLatch = CountDownLatch(1)
        val peerErrors = AtomicReference<String>()
        val peer = Thread {
            try {
                server.accept().use { s ->
                    s.soTimeout = 20_000
                    val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                    val out = s.getOutputStream()
                    // 1) Read our SEND, ack it.
                    val head1 = readHead(input)
                    val tx1 = head1.firstOrNull()?.split(" ")?.getOrNull(1).orEmpty()
                    val body1 = readToMarker(input, tx1)
                    chunksSeen += body1
                    // OutChunk serialization appends CRLF before the marker; the
                    // body slice read here is line-based, so compare trimmed.
                    respondOk(s, tx1, "msrp://127.0.0.1:$port/self;tcp")
                    // 2) Send an inbound SEND to us; expect our auto-200.
                    val inbound = RcsMsrpFraming.splitSend(
                        toPath = "msrp://127.0.0.1:$port/self;tcp",
                        fromPath = "msrp://127.0.0.1:$port/peer;tcp",
                        payload = "inbound hello".toByteArray(Charsets.UTF_8),
                        messageId = "pass2-inbound-1",
                    ).single()
                    out.write(RcsMsrpFraming.serializeChunk(inbound))
                    out.flush()
                    val respHead = readHead(input)
                    chunksSeen += respHead.joinToString("\n")
                    readToMarker(input, inbound.txid)
                    // 3) REPORT frame: queued by the reader, no response.
                    val report = RcsMsrpFraming.buildReport(
                        toPath = "msrp://127.0.0.1:$port/self;tcp",
                        fromPath = "msrp://127.0.0.1:$port/peer;tcp",
                        messageId = "pass2-msg-9", statusCode = 200, reason = "OK",
                        byteRange = "1-5/5",
                    )
                    out.write(report)
                    out.flush()
                    // 4) AUTH frame: answered 501.
                    val authTx = "authtx123456"
                    out.write(
                        ("MSRP $authTx AUTH\r\nTo-Path: msrp://127.0.0.1:$port/self;tcp\r\n" +
                            "From-Path: msrp://127.0.0.1:$port/peer;tcp\r\n\r\n" +
                            "-------$authTx\$\r\n").toByteArray(Charsets.UTF_8),
                    )
                    out.flush()
                    // Read frames until the 501 AUTH response arrives; our
                    // keepalive/report frames may interleave ahead of it.
                    var saw501 = false
                    var guard = 0
                    while (!saw501 && guard++ < 8) {
                        val frameHead = readHead(input)
                        if (frameHead.isEmpty()) break
                        chunksSeen += frameHead.joinToString("\n")
                        val frameTx = frameHead.firstOrNull()?.split(" ")?.getOrNull(1).orEmpty()
                        readToMarker(input, frameTx)
                        if (frameHead.joinToString("\n").contains("501")) saw501 = true
                    }
                    chunksSeen += if (saw501) "SAW-501" else "NO-501"
                    // Wait for the test to finish its assertions, then close.
                    peerLatch.await(20, TimeUnit.SECONDS)
                }
            } catch (e: Throwable) {
                peerErrors.set(e.toString())
            }
        }
        peer.isDaemon = true
        peer.start()

        val inboundBodies = CopyOnWriteArrayList<String>()
        val conn = RcsMsrp.connect(session(port), context) { contentType, body ->
            inboundBodies += "$contentType:${body.toString(Charsets.UTF_8)}"
        }
        assertNotNull(conn, "connect failed")
        assertFalse(conn.isClosed())

        // SEND through the persistent connection and await its ack.
        assertTrue(conn.sendCpim("persistent hello".toByteArray(Charsets.UTF_8)))
        // Empty payload is refused without touching the socket.
        assertFalse(conn.sendCpim(ByteArray(0)))

        // Inbound SEND is delivered and auto-200ed (peer records the response).
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && inboundBodies.isEmpty()) Thread.sleep(25)
        assertEquals(listOf("message/cpim:inbound hello"), inboundBodies.toList())

        // Keepalive + REPORT go out on the same socket.
        assertTrue(conn.sendKeepalive())
        assertTrue(conn.sendReport("pass2-msg-9", 200, "OK", "1-5/5"))

        peerLatch.countDown()
        peer.join(20_000)
        assertNull(peerErrors.get())
        val peerWire = chunksSeen.joinToString("\n")
        assertTrue(peerWire.contains("persistent hello"), peerWire.take(500))
        assertTrue(peerWire.contains("200 OK"), peerWire.take(500))
        assertTrue(peerWire.contains("SAW-501"), peerWire.take(800))

        conn.close()
        assertTrue(conn.isClosed())
        conn.close()
        assertFalse(conn.sendCpim("after close".toByteArray()))
        assertFalse(conn.sendKeepalive())
        assertFalse(conn.sendReport("m", 200, "OK", "1-1/1"))
        server.close()
    }

    @Test
    fun connectSecureSessionWithoutFingerprintDowngradesToPlaintext() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val done = serveOnce(server) { s ->
            s.soTimeout = 15_000
            val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
            val head = readHead(input)
            val txid = head.firstOrNull()?.split(" ")?.getOrNull(1).orEmpty()
            readToMarker(input, txid)
            respondOk(s, txid, "msrp://127.0.0.1:$port/self;tcp")
        }
        // Secure session, but no fingerprint was stashed for this call:
        // connect() downgrades to plaintext rather than failing.
        val conn = RcsMsrp.connect(session(port).copy(msrpSecure = true), context) { _, _ -> }
        assertNotNull(conn)
        assertTrue(conn.sendCpim("downgraded hello".toByteArray(Charsets.UTF_8)))
        conn.close()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        server.close()
    }

    // --- wrapAccepted ---

    @Test
    fun wrapAcceptedDeliversInboundChunk() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val accepted = CountDownLatch(1)
        var serverSocket: Socket? = null
        Thread {
            runCatching {
                serverSocket = server.accept()
            }
            accepted.countDown()
        }.apply { isDaemon = true; start() }

        val client = Socket("127.0.0.1", port)
        assertTrue(accepted.await(10, TimeUnit.SECONDS))
        val serverSide = serverSocket!!

        val delivered = CopyOnWriteArrayList<String>()
        val head = RcsMsrpListen.AcceptedHead(
            listOf(
                "MSRP wrappedtx1 SEND",
                "To-Path: msrp://127.0.0.1:$port/self;tcp",
                "From-Path: msrp://127.0.0.1:$port/peer;tcp",
                "Message-ID: wrapped-1",
                "Byte-Range: 1-7/7",
                "Content-Type: message/cpim",
            ),
        )
        assertTrue(head.replayBytes().toString(Charsets.UTF_8).startsWith("MSRP wrappedtx1 SEND\r\n"))
        val conn = RcsMsrp.wrapAccepted(
            serverSide, head,
            localPath = "msrp://127.0.0.1:$port/self;tcp",
            remotePath = "msrp://127.0.0.1:$port/peer;tcp",
        ) { _, body -> delivered += body.toString(Charsets.UTF_8) }
        // The reader replays the head; the client supplies body + marker.
        client.getOutputStream().apply {
            write("wrapped".toByteArray(Charsets.UTF_8))
            write("\r\n-------wrappedtx1\$\r\n".toByteArray(Charsets.UTF_8))
            flush()
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && delivered.isEmpty()) Thread.sleep(25)
        assertEquals(listOf("wrapped"), delivered.toList())
        // The auto-200 for the wrapped SEND comes back to the client.
        client.soTimeout = 15_000
        val clientIn = client.getInputStream().bufferedReader(Charsets.UTF_8)
        val responseHead = readHead(clientIn)
        assertTrue(responseHead.joinToString("\n").contains("200 OK"), responseHead.toString())
        conn.close()
        client.close()
        server.close()
    }
}
