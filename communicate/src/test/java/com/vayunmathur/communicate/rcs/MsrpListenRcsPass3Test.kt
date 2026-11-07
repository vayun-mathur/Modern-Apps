package com.vayunmathur.communicate.rcs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsImsNetwork
import com.vayunmathur.communicate.data.rcs.RcsMsrpListen
import com.vayunmathur.communicate.data.rcs.RcsMsrpTls
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pass 3: MSRP listen routing branches not covered by the pass-2 socket
 * tests — plaintext-on-secure rejection, non-MSRP/EOF sockets, 481s,
 * pending-path drops, and full TLS accepts (pinned and on plain paths).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RcsSlice3Pass2ImsShadow::class])
class MsrpListenRcsPass3Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private data class Accepted(val conversationId: String, val socket: Socket, val head: RcsMsrpListen.AcceptedHead)

    private val accepted = CopyOnWriteArrayList<Accepted>()
    private val acceptedLatch = CountDownLatch(1)

    @BeforeTest fun setUp() {
        accepted.clear()
        runBlocking {
            RcsImsNetwork.reset()
            assertTrue(
                RcsMsrpListen.ensureListening(context) { conv, socket, head ->
                    accepted += Accepted(conv, socket, head)
                    acceptedLatch.countDown()
                },
            )
        }
        assertTrue(RcsMsrpListen.isListening())
    }

    @AfterTest fun tearDown() {
        accepted.forEach { runCatching { it.socket.close() } }
        RcsMsrpListen.stop()
        RcsImsNetwork.reset()
    }

    private fun port(): Int = assertNotNull(RcsMsrpListen.listenPort())

    private fun sendRaw(bytes: ByteArray): Socket {
        val socket = Socket("127.0.0.1", port())
        socket.soTimeout = 15_000
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
        return socket
    }

    private fun sendHead(txid: String, toPath: String?): Socket {
        val head = buildString {
            append("MSRP $txid SEND\r\n")
            if (toPath != null) append("To-Path: $toPath\r\n")
            append("From-Path: msrp://10.0.0.9/peer;tcp\r\n")
            append("Message-ID: m-$txid\r\n")
            append("Content-Type: text/plain\r\n\r\n")
        }
        return sendRaw(head.toByteArray(Charsets.UTF_8))
    }

    private fun readUntilClosed(socket: Socket): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (true) {
                val n = socket.getInputStream().read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > 8192) break
            }
        } catch (_: Exception) {
            // Timeout/reset also prove the server closed its side.
        }
        return out.toString(Charsets.UTF_8)
    }

    @Test fun acceptedHeadReplayBytes() {
        val head = RcsMsrpListen.AcceptedHead(listOf("MSRP t SEND", "To-Path: msrp://x/y;tcp"))
        assertEquals(
            "MSRP t SEND\r\nTo-Path: msrp://x/y;tcp\r\n\r\n",
            head.replayBytes().toString(Charsets.UTF_8),
        )
        assertEquals("\r\n", RcsMsrpListen.AcceptedHead(emptyList()).replayBytes().toString(Charsets.UTF_8))
    }

    @Test fun plaintextOnSecurePathRejectedSilently() = runBlocking {
        val path = RcsMsrpListen.advertisePath(context, "conv-secure", secure = true) { _, _, _ -> }
        assertNotNull(path)
        val socket = sendHead("txsec", path)
        val response = readUntilClosed(socket)
        // No 481 for downgrade attempts: the socket is simply dropped.
        assertTrue(!response.contains("481"), response)
        assertTrue(accepted.isEmpty())
        socket.close()
    }

    @Test fun unknownAndMissingPathsGet481() {
        val unknown = sendHead("txunk", "msrp://0.0.0.0:${port()}/nope;tcp")
        assertTrue(readUntilClosed(unknown).contains("MSRP txunk 481"))
        unknown.close()
        val missing = sendHead("txmiss", null)
        assertTrue(readUntilClosed(missing).contains("MSRP txmiss 481"))
        missing.close()
        assertTrue(accepted.isEmpty())
    }

    @Test fun nonMsrpAndEofSocketsDroppedListenerSurvives() {
        val http = sendRaw("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        readUntilClosed(http)
        http.close()
        val eof = Socket("127.0.0.1", port())
        eof.close()
        // Listener still works afterwards.
        assertTrue(RcsMsrpListen.isListening())
        assertTrue(accepted.isEmpty())
    }

    @Test fun droppedPendingPathNoLongerMatches() = runBlocking {
        val path = RcsMsrpListen.advertisePath(context, "conv-drop") { _, _, _ -> }
        assertNotNull(path)
        RcsMsrpListen.notePeerFingerprint("conv-drop", "AA:BB")
        RcsMsrpListen.dropPending("conv-drop")
        val socket = sendHead("txdrop", path)
        assertTrue(readUntilClosed(socket).contains("MSRP txdrop 481"))
        socket.close()
        assertTrue(accepted.isEmpty())
    }

    @Test fun tlsAcceptOnSecurePath() = runBlocking {
        val path = RcsMsrpListen.advertisePath(context, "conv-tls", secure = true) { _, _, _ -> }
        assertNotNull(path)
        val serverIdentity = RcsMsrpTls.ensureIdentity(context)
        assertNotNull(serverIdentity)
        val ssl = RcsMsrpTls.clientSocket(context, "127.0.0.1", port(), serverIdentity.fingerprint)
        assertNotNull(ssl)
        ssl.getOutputStream().write(
            ("MSRP txtls SEND\r\nTo-Path: $path\r\nFrom-Path: msrps://10.0.0.9/peer;tcp\r\n\r\n")
                .toByteArray(Charsets.UTF_8),
        )
        ssl.getOutputStream().flush()
        assertTrue(acceptedLatch.await(15, TimeUnit.SECONDS), "no TLS accept")
        val got = accepted.first()
        assertEquals("conv-tls", got.conversationId)
        assertTrue(got.socket is SSLSocket)
        assertTrue(got.head.lines.first().startsWith("MSRP txtls SEND"))
        ssl.close()
    }

    @Test fun tlsOnPlainPathAcceptedAnyway() = runBlocking {
        val path = RcsMsrpListen.advertisePath(context, "conv-plain-tls", secure = false) { _, _, _ -> }
        assertNotNull(path)
        val serverIdentity = RcsMsrpTls.ensureIdentity(context)
        assertNotNull(serverIdentity)
        val ssl = RcsMsrpTls.clientSocket(context, "127.0.0.1", port(), serverIdentity.fingerprint)
        assertNotNull(ssl)
        ssl.getOutputStream().write(
            ("MSRP txtls2 SEND\r\nTo-Path: $path\r\nFrom-Path: msrps://10.0.0.9/peer;tcp\r\n\r\n")
                .toByteArray(Charsets.UTF_8),
        )
        ssl.getOutputStream().flush()
        assertTrue(acceptedLatch.await(15, TimeUnit.SECONDS), "no TLS-on-plain accept")
        assertEquals("conv-plain-tls", accepted.first().conversationId)
        ssl.close()
    }
}
