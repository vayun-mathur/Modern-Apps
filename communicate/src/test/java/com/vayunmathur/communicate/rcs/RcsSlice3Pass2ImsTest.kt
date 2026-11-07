package com.vayunmathur.communicate.rcs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsDirectSip
import com.vayunmathur.communicate.data.rcs.RcsImsNetwork
import com.vayunmathur.communicate.data.rcs.RcsMsrp
import com.vayunmathur.communicate.data.rcs.RcsMsrpListen
import com.vayunmathur.communicate.data.rcs.RcsMsrpTls
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-3 pass 2 on a fake IMS PDN ([RcsSlice3Pass2ImsShadow]): the IMS
 * network scan/request, IMS-bound sockets, the MSRP listen/accept loop
 * (matched, unknown and TLS-on-plaintext routing), and a full direct-SIP
 * REGISTER + MESSAGE exchange against a loopback P-CSCF.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RcsSlice3Pass2ImsShadow::class])
class RcsSlice3Pass2ImsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @AfterTest
    fun tearDown() {
        RcsMsrpListen.stop()
        RcsDirectSip.stop()
        RcsImsNetwork.reset()
    }

    @Test
    fun imsNetworkScanFindsFakePdn() = runTest {
        RcsImsNetwork.reset()
        val network = RcsImsNetwork.imsNetwork(context, 1)
        assertNotNull(network)
        // Cached on the second call.
        assertEquals(network, RcsImsNetwork.imsNetwork(context, 1))
        assertEquals("0.0.0.0", RcsImsNetwork.lastLocalIp)
        assertEquals(listOf("0.0.0.0"), RcsImsNetwork.localIps(context, network))
        val listen = RcsImsNetwork.listenSocket(context)
        assertNotNull(listen)
        assertEquals("0.0.0.0", listen.localIp)
        assertTrue(listen.localPort > 0)
        assertEquals("msrp://0.0.0.0:${listen.localPort}/sess-9;tcp", listen.msrpPath("sess-9"))
        listen.close()
        RcsImsNetwork.reset()
        assertNull(RcsImsNetwork.lastLocalIp)
    }

    @Test
    fun imsSocketConnectsToLoopback() = runTest {
        val server = ServerSocket(0)
        val accepted = CountDownLatch(1)
        Thread {
            runCatching { server.accept().use { s -> s.getOutputStream().write(42) } }
            accepted.countDown()
        }.apply { isDaemon = true; start() }
        val socket = RcsImsNetwork.createSocket(context, "127.0.0.1", server.localPort, 5_000)
        assertNotNull(socket)
        assertTrue(socket.isConnected)
        assertEquals(42, socket.getInputStream().read())
        socket.close()
        assertTrue(accepted.await(10, TimeUnit.SECONDS))
        server.close()
    }

    // --- MSRP listen / accept routing ---

    private fun sendHeadAndBody(port: Int, head: String, body: String?): Socket {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 15_000
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.flush()
        if (body != null) {
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush()
        }
        return socket
    }

    @Test
    fun listenAcceptRoutesMatchedPathAndRejectsUnknown() = runTest {
        val acceptedLatch = CountDownLatch(1)
        val acceptedConv = AtomicReference<String>()
        val acceptedHead = AtomicReference<RcsMsrpListen.AcceptedHead>()
        var acceptedSocket: Socket? = null
        assertTrue(
            RcsMsrpListen.ensureListening(context) { conv, socket, head ->
                acceptedConv.set(conv)
                acceptedHead.set(head)
                acceptedSocket = socket
                acceptedLatch.countDown()
            },
        )
        assertTrue(RcsMsrpListen.isListening())
        val port = RcsMsrpListen.listenPort()
        assertNotNull(port)
        assertEquals("0.0.0.0", RcsMsrpListen.listenIp())
        // Idempotent.
        assertTrue(RcsMsrpListen.ensureListening(context) { _, _, _ -> })

        val path = RcsMsrpListen.advertisePath(context, "ims-conv-1") { _, _, _ -> }
        assertNotNull(path)
        assertTrue(path.startsWith("msrp://0.0.0.0:$port/"))

        // Unknown path: 481 and close.
        val txUnknown = "unknowntx1"
        val rogue = sendHeadAndBody(
            port,
            "MSRP $txUnknown SEND\r\nTo-Path: msrp://0.0.0.0:$port/nope;tcp\r\n" +
                "From-Path: msrp://10.0.0.9/peer;tcp\r\n\r\n",
            null,
        )
        val rogueIn = rogue.getInputStream().bufferedReader(Charsets.UTF_8)
        val responseLine = rogueIn.readLine()
        assertTrue(responseLine?.contains("481") == true, "response=$responseLine")
        rogue.close()

        // Matched path: the accept callback receives the socket + head, and
        // wrapping it in an MSRP connection delivers the pending body.
        val tx = "matchedtx1"
        val client = sendHeadAndBody(
            port,
            "MSRP $tx SEND\r\nTo-Path: $path\r\nFrom-Path: msrp://10.0.0.9:2855/peer;tcp\r\n" +
                "Message-ID: ims-msg-1\r\nByte-Range: 1-5/5\r\nContent-Type: message/cpim\r\n\r\n",
            null,
        )
        assertTrue(acceptedLatch.await(15, TimeUnit.SECONDS), "no accept callback")
        assertEquals("ims-conv-1", acceptedConv.get())
        assertTrue(acceptedHead.get().lines.first().startsWith("MSRP $tx SEND"))
        val delivered = CopyOnWriteArrayList<String>()
        val conn = RcsMsrp.wrapAccepted(
            acceptedSocket!!, acceptedHead.get(), path,
            "msrp://10.0.0.9:2855/peer;tcp",
        ) { _, body -> delivered += body.toString(Charsets.UTF_8) }
        client.getOutputStream().apply {
            write("hello".toByteArray(Charsets.UTF_8))
            write("\r\n-------$tx\$\r\n".toByteArray(Charsets.UTF_8))
            flush()
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && delivered.isEmpty()) Thread.sleep(25)
        assertEquals(listOf("hello"), delivered.toList())
        conn.close()
        client.close()
        RcsMsrpListen.stop()
        assertFalse(RcsMsrpListen.isListening())
        assertNull(RcsMsrpListen.listenPort())
    }

    @Test
    fun listenRejectsPlaintextOnSecurePath() = runTest {
        val acceptedLatch = CountDownLatch(1)
        assertTrue(RcsMsrpListen.ensureListening(context) { _, _, _ -> acceptedLatch.countDown() })
        val port = RcsMsrpListen.listenPort()!!
        val securePath = RcsMsrpListen.advertisePath(
            context, "ims-conv-secure", secure = true, peerFingerprint = "AA:BB",
        ) { _, _, _ -> }
        assertNotNull(securePath)
        val client = sendHeadAndBody(
            port,
            "MSRP securetx1 SEND\r\nTo-Path: $securePath\r\n" +
                "From-Path: msrp://10.0.0.9/peer;tcp\r\n\r\n",
            null,
        )
        // The socket is closed without an accept callback or a 481.
        val read = runCatching { client.getInputStream().read() }.getOrDefault(-2)
        assertEquals(-1, read)
        assertEquals(1L, acceptedLatch.count)
        client.close()
        // Dropping the pending path makes the same path unknown afterwards.
        RcsMsrpListen.dropPending("ims-conv-secure")
        RcsMsrpListen.stop()
    }

    @Test
    fun listenTlsByteOnPlainPathAttemptsHandshakeAndCloses() = runTest {
        val acceptedLatch = CountDownLatch(1)
        assertTrue(RcsMsrpListen.ensureListening(context) { _, _, _ -> acceptedLatch.countDown() })
        val port = RcsMsrpListen.listenPort()!!
        val plainPath = RcsMsrpListen.advertisePath(context, "ims-conv-tls") { _, _, _ -> }
        assertNotNull(plainPath)
        // A TLS ClientHello first byte routes to the TLS branch; the junk
        // that follows fails the handshake and the socket closes.
        val client = Socket("127.0.0.1", port)
        client.soTimeout = 15_000
        client.getOutputStream().apply {
            write(0x16)
            write("not-a-client-hello".toByteArray(Charsets.UTF_8))
            flush()
        }
        val read = runCatching { client.getInputStream().read() }.getOrDefault(-1)
        assertEquals(-1, read)
        assertEquals(1L, acceptedLatch.count)
        client.close()
        RcsMsrpListen.stop()
    }

    @Test
    fun listenTlsAcceptWithPinnedClient() = runTest {
        val identity = RcsMsrpTls.ensureIdentity(context)
        assertNotNull(identity)
        val acceptedLatch = CountDownLatch(1)
        val acceptedConv = AtomicReference<String>()
        val acceptedHead = AtomicReference<RcsMsrpListen.AcceptedHead>()
        var acceptedSocket: Socket? = null
        assertTrue(
            RcsMsrpListen.ensureListening(context) { conv, socket, head ->
                acceptedConv.set(conv)
                acceptedHead.set(head)
                acceptedSocket = socket
                acceptedLatch.countDown()
            },
        )
        val port = RcsMsrpListen.listenPort()!!
        val securePath = RcsMsrpListen.advertisePath(
            context, "ims-conv-tls-ok", secure = true, peerFingerprint = identity.fingerprint,
        ) { _, _, _ -> }
        assertNotNull(securePath)

        val client = RcsMsrpTls.clientSocket(context, "127.0.0.1", port, identity.fingerprint)
        assertNotNull(client, "TLS client could not handshake with the listen socket")
        val tx = "tlstx12345"
        client.getOutputStream().apply {
            write(
                ("MSRP $tx SEND\r\nTo-Path: $securePath\r\n" +
                    "From-Path: msrp://10.0.0.9:2855/peer;tcp\r\n" +
                    "Message-ID: ims-tls-1\r\nByte-Range: 1-4/4\r\n" +
                    "Content-Type: message/cpim\r\n\r\n").toByteArray(Charsets.UTF_8),
            )
            flush()
        }
        assertTrue(acceptedLatch.await(20, TimeUnit.SECONDS), "TLS accept never routed")
        assertEquals("ims-conv-tls-ok", acceptedConv.get())
        assertTrue(acceptedSocket is javax.net.ssl.SSLSocket)

        // The accepted connection delivers the chunk body like a plain one.
        val delivered = CopyOnWriteArrayList<String>()
        val conn = RcsMsrp.wrapAccepted(
            acceptedSocket!!, acceptedHead.get(), securePath,
            "msrp://10.0.0.9:2855/peer;tcp",
        ) { _, body -> delivered += body.toString(Charsets.UTF_8) }
        client.getOutputStream().apply {
            write("data".toByteArray(Charsets.UTF_8))
            write("\r\n-------$tx\$\r\n".toByteArray(Charsets.UTF_8))
            flush()
        }
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && delivered.isEmpty()) Thread.sleep(25)
        assertEquals(listOf("data"), delivered.toList())
        conn.close()
        client.close()
        RcsMsrpListen.stop()
    }

    @Test
    fun listenTlsAcceptRejectsWrongFingerprint() = runTest {
        val identity = RcsMsrpTls.ensureIdentity(context)
        assertNotNull(identity)
        val acceptedLatch = CountDownLatch(1)
        assertTrue(RcsMsrpListen.ensureListening(context) { _, _, _ -> acceptedLatch.countDown() })
        val port = RcsMsrpListen.listenPort()!!
        // The pending path pins a fingerprint our cert does not have.
        val securePath = RcsMsrpListen.advertisePath(
            context, "ims-conv-tls-bad", secure = true,
            peerFingerprint = "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
        ) { _, _, _ -> }
        assertNotNull(securePath)
        val client = RcsMsrpTls.clientSocket(context, "127.0.0.1", port, identity.fingerprint)
        assertNotNull(client)
        client.getOutputStream().apply {
            write(
                ("MSRP badtx123 SEND\r\nTo-Path: $securePath\r\n" +
                    "From-Path: msrp://10.0.0.9:2855/peer;tcp\r\n\r\n").toByteArray(Charsets.UTF_8),
            )
            flush()
        }
        // The server closes after the fingerprint mismatch; no accept fires.
        val read = runCatching { client.getInputStream().read() }.getOrDefault(-1)
        assertEquals(-1, read)
        assertEquals(1L, acceptedLatch.count)
        client.close()
        RcsMsrpListen.stop()
    }

    @Test
    fun listenFirstByteEofClosesQuietly() = runTest {
        val acceptedLatch = CountDownLatch(1)
        assertTrue(RcsMsrpListen.ensureListening(context) { _, _, _ -> acceptedLatch.countDown() })
        val port = RcsMsrpListen.listenPort()!!
        // Connect and immediately close: the router reads EOF and drops it.
        Socket("127.0.0.1", port).close()
        Thread.sleep(500)
        assertEquals(1L, acceptedLatch.count)
        RcsMsrpListen.stop()
    }

    // --- Direct SIP over the fake PDN ---

    @Test
    fun directSipRegistersAndExchangesMessages() = kotlinx.coroutines.runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        // The IMS shadow advertises the P-CSCF on 127.0.0.1:5060; bind there
        // instead by pointing the accepted server at the shadow's port.
        server.close()
        val pcscf = ServerSocket(5060)
        val requests = CopyOnWriteArrayList<String>()
        val inboundSent = CountDownLatch(1)
        val serverDone = CountDownLatch(1)
        Thread {
            try {
                pcscf.accept().use { s ->
                    s.soTimeout = 20_000
                    val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                    val out = s.getOutputStream()
                    fun readRequest(): List<String> {
                        val lines = mutableListOf<String>()
                        var line = input.readLine() ?: return lines
                        // Content-Length body, when present.
                        var contentLength = 0
                        while (line.isNotEmpty()) {
                            lines += line
                            if (line.startsWith("Content-Length:", true)) {
                                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                            line = input.readLine() ?: break
                        }
                        if (contentLength > 0) {
                            val chars = CharArray(contentLength)
                            var read = 0
                            while (read < contentLength) {
                                val r = input.read(chars, read, contentLength - read)
                                if (r < 0) break
                                read += r
                            }
                            lines += String(chars, 0, read)
                        }
                        return lines
                    }
                    fun branchOf(lines: List<String>): String =
                        lines.firstOrNull { it.startsWith("Via:", true) }
                            ?.substringAfter("branch=")?.substringBefore(";")?.trim().orEmpty()
                    fun respond(lines: List<String>, code: Int, reason: String, extra: String = "") {
                        val branch = branchOf(lines)
                        val callId = lines.firstOrNull { it.startsWith("Call-ID:", true) }.orEmpty()
                        val cseq = lines.firstOrNull { it.startsWith("CSeq:", true) }
                            ?.substringAfter(":")?.trim().orEmpty()
                        out.write(
                            ("SIP/2.0 $code $reason\r\nVia: SIP/2.0/TCP 127.0.0.1;branch=$branch\r\n" +
                                "$callId\r\nCSeq: $cseq\r\n$extra" +
                                "Contact: <sip:127.0.0.1:5060>;expires=600\r\n" +
                                "Expires: 600\r\nContent-Length: 0\r\n\r\n").toByteArray(Charsets.UTF_8),
                        )
                        out.flush()
                    }
                    // 1) REGISTER -> 200 (no auth challenge in this script).
                    val register = readRequest()
                    requests += register.joinToString("\n")
                    respond(register, 200, "OK")
                    // 2) Inbound MESSAGE pushed to the client.
                    out.write(
                        ("MESSAGE sip:me@rcs SIP/2.0\r\nVia: SIP/2.0/TCP 127.0.0.1;branch=z9hG4bKinbound\r\n" +
                            "From: <sip:peer@rcs>;tag=p1\r\nTo: <sip:me@rcs>\r\n" +
                            "Call-ID: inbound-1@pcscf\r\nCSeq: 1 MESSAGE\r\n" +
                            "Content-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello")
                            .toByteArray(Charsets.UTF_8),
                    )
                    out.flush()
                    inboundSent.countDown()
                    // 3) Outbound MESSAGE from the client -> 200 echoing its branch.
                    val message = readRequest()
                    requests += message.joinToString("\n")
                    respond(message, 200, "OK")
                    // 4) Deregistration REGISTER (expires=0) -> 200, then close.
                    val bye = readRequest()
                    requests += bye.joinToString("\n")
                    respond(bye, 200, "OK")
                }
            } catch (e: Throwable) {
                requests += "SERVER-ERROR: $e"
            }
            serverDone.countDown()
        }.apply { isDaemon = true; start() }

        // Shadow P-CSCF is 127.0.0.1 (default SIP port on the link props).
        val inbound = CopyOnWriteArrayList<Triple<String, String, String>>()
        RcsDirectSip.onInboundMessage = { startLine, headers, body ->
            inbound += Triple(startLine, headers, body.toString(Charsets.UTF_8))
        }
        val started = RcsDirectSip.start(context, 1)
        assertTrue(started, "direct SIP did not register: $requests")
        assertTrue(RcsDirectSip.isRegistered())
        assertTrue(inboundSent.await(15, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && inbound.isEmpty()) Thread.sleep(25)
        assertEquals(1, inbound.size)
        assertEquals("hello", inbound.first().third)
        assertTrue(inbound.first().first.startsWith("MESSAGE "))

        val sentOk = RcsDirectSip.sendMessage(
            "MESSAGE sip:peer@rcs SIP/2.0",
            "To: <sip:peer@rcs>\r\nContent-Type: message/cpim\r\n",
            "direct hello".toByteArray(Charsets.UTF_8),
        )
        assertTrue(sentOk, "MESSAGE was not acked: $requests")

        RcsDirectSip.stop()
        assertFalse(RcsDirectSip.isRegistered())
        assertTrue(serverDone.await(20, TimeUnit.SECONDS), "server: $requests")
        val registerWire = requests.first()
        assertTrue(registerWire.contains("REGISTER sip:127.0.0.1 SIP/2.0"), registerWire)
        assertTrue(registerWire.contains("Expires: 600"), registerWire)
        assertTrue(requests.any { it.contains("MESSAGE sip:peer@rcs SIP/2.0") }, requests.toString())
        assertTrue(requests.any { it.contains("expires=0") }, requests.mapIndexed { i, r -> "[$i] $r" }.toString())
        pcscf.close()
        Unit
    }
}
