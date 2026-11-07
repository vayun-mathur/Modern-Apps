package com.vayunmathur.communicate.rcs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsDirectSip
import com.vayunmathur.communicate.data.rcs.RcsImsNetwork
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pass 3: direct-SIP registration failure modes against a loopback
 * P-CSCF on the shadow IMS PDN — rejection, unanswerable AKA
 * challenge, failed authenticated REGISTER, MESSAGE errors, and
 * deregistration. (The happy paths live in the pass-2 IMS tests.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RcsSlice3Pass2ImsShadow::class])
class DirectSipRcsPass3Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @AfterTest fun tearDown() {
        RcsDirectSip.stop()
        RcsDirectSip.onInboundMessage = null
        RcsImsNetwork.reset()
    }

    /** One-connection P-CSCF script; [handler] maps request text to a response or null (close). */
    private class Pcscf(private val handler: (String) -> String?) {
        val requests = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        fun serve(server: ServerSocket) {
            Thread {
                try {
                    server.accept().use { s ->
                        s.soTimeout = 20_000
                        val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                        val out = s.getOutputStream()
                        while (true) {
                            val lines = mutableListOf<String>()
                            var line = input.readLine() ?: break
                            var contentLength = 0
                            while (line.isNotEmpty()) {
                                lines += line
                                if (line.startsWith("Content-Length:", true)) {
                                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                                }
                                line = input.readLine() ?: break
                            }
                            if (lines.isEmpty()) break
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
                            val request = lines.joinToString("\r\n")
                            requests += request
                            val response = handler(request) ?: break
                            // Echo Via branch / Call-ID / CSeq like a real P-CSCF.
                            val branch = lines.firstOrNull { it.startsWith("Via:", true) }
                                ?.substringAfter("branch=")?.substringBefore(";")?.trim().orEmpty()
                            val callId = lines.firstOrNull { it.startsWith("Call-ID:", true) }.orEmpty()
                            val cseq = lines.firstOrNull { it.startsWith("CSeq:", true) }.orEmpty()
                            out.write(
                                ("$response\r\nVia: SIP/2.0/TCP 127.0.0.1;branch=$branch\r\n" +
                                    "$callId\r\n$cseq\r\nContent-Length: 0\r\n\r\n").toByteArray(Charsets.UTF_8),
                            )
                            out.flush()
                        }
                    }
                } catch (_: Throwable) {
                    // Client went away mid-script.
                }
                done.countDown()
            }.apply { isDaemon = true; start() }
        }
    }

    private suspend fun withPcscf(handler: (String) -> String?, block: suspend (Pcscf) -> Unit) {
        val server = ServerSocket(5060)
        val pcscf = Pcscf(handler)
        pcscf.serve(server)
        try {
            block(pcscf)
        } finally {
            runCatching { server.close() }
            RcsDirectSip.stop()
        }
    }

    @Test fun idleEdgesWithoutRegistration() = runBlocking {
        assertFalse(RcsDirectSip.start(context, -1))
        assertFalse(RcsDirectSip.isRegistered())
        assertFalse(
            RcsDirectSip.sendMessage("MESSAGE sip:x@rcs SIP/2.0", "To: <sip:x@rcs>\r\n", "hi".toByteArray()),
        )
        RcsDirectSip.stop()
        RcsDirectSip.stop()
        assertFalse(RcsDirectSip.isRegistered())
    }

    @Test fun registerRejected403FailsStart() = runBlocking {
        withPcscf(handler = { "SIP/2.0 403 Forbidden" }) { pcscf ->
            assertFalse(RcsDirectSip.start(context, 1))
            assertFalse(RcsDirectSip.isRegistered())
            assertTrue(pcscf.done.await(15, TimeUnit.SECONDS), "requests=${pcscf.requests}")
            assertTrue(pcscf.requests.first().contains("REGISTER"))
        }
    }

    @Test fun challengeWithoutAkaFailsStart() = runBlocking {
        withPcscf(
            handler = { request ->
                if (request.contains("Authorization:")) "SIP/2.0 200 OK"
                else "SIP/2.0 401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"rcs\", nonce=\"abc123\", algorithm=MD5, qop=\"auth\""
            },
        ) { pcscf ->
            // No Telephony shadow here: getIccAuthentication returns null, so
            // the AKA challenge cannot be answered and start must fail.
            assertFalse(RcsDirectSip.start(context, 1))
            assertFalse(RcsDirectSip.isRegistered())
            assertTrue(pcscf.done.await(15, TimeUnit.SECONDS), "requests=${pcscf.requests}")
            // Only the initial REGISTER was sent (plus stop()'s deregister).
            assertTrue(pcscf.requests.none { it.contains("Authorization:") }, "${pcscf.requests}")
        }
    }

    @Test fun messageErrorResponseFailsSendAndStopDeregisters() = runBlocking {
        var sawMessage = false
        withPcscf(
            handler = { request ->
                when {
                    request.startsWith("MESSAGE") -> {
                        sawMessage = true
                        "SIP/2.0 404 Not Found"
                    }
                    request.contains("Expires: 0") -> "SIP/2.0 200 OK"
                    else -> "SIP/2.0 200 OK\r\nContact: <sip:127.0.0.1:5060>;expires=600\r\nExpires: 600"
                }
            },
        ) { pcscf ->
            assertTrue(RcsDirectSip.start(context, 1))
            assertTrue(RcsDirectSip.isRegistered())
            assertFalse(
                RcsDirectSip.sendMessage(
                    "MESSAGE sip:peer@rcs SIP/2.0",
                    "To: <sip:peer@rcs>\r\nContent-Type: message/cpim\r\n",
                    "hello".toByteArray(Charsets.UTF_8),
                ),
            )
            assertTrue(sawMessage)
            RcsDirectSip.stop()
            assertFalse(RcsDirectSip.isRegistered())
            assertTrue(pcscf.done.await(15, TimeUnit.SECONDS), "requests=${pcscf.requests}")
            assertTrue(pcscf.requests.any { it.contains("Expires: 0") }, "${pcscf.requests}")
            assertEquals(3, pcscf.requests.size)
        }
    }
}
