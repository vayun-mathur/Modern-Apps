package com.vayunmathur.communicate.rcs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsDirectSip
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice-3 pass 2: direct-SIP AKA registration — 401 challenge, digest
 * REGISTER built from the ISIM response (shadow TelephonyManager), 200 OK,
 * deregistration, and a full restart after stop (regression coverage for
 * the scope-cancellation restart bug).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [RcsSlice3Pass2ImsShadow::class, RcsSlice3Pass2TelephonyShadow::class],
)
class RcsSlice3Pass2DirectSipAuthTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @AfterTest
    fun tearDown() {
        RcsDirectSip.stop()
    }

    private class PcscfScript(val challengeFirst: Boolean) {
        val requests = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<String>()

        fun serve(server: ServerSocket) {
            Thread {
                try {
                    server.accept().use { s ->
                        s.soTimeout = 20_000
                        val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                        val out = s.getOutputStream()
                        fun readRequest(): List<String> {
                            val lines = mutableListOf<String>()
                            var line = input.readLine() ?: return lines
                            while (line.isNotEmpty()) {
                                lines += line
                                line = input.readLine() ?: break
                            }
                            return lines
                        }
                        fun respond(lines: List<String>, code: Int, reason: String, extra: String = "") {
                            val branch = lines.firstOrNull { it.startsWith("Via:", true) }
                                ?.substringAfter("branch=")?.substringBefore(";")?.trim().orEmpty()
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
                        var first = true
                        while (true) {
                            val request = readRequest()
                            if (request.isEmpty()) break
                            requests += request.joinToString("\n")
                            if (first && challengeFirst) {
                                first = false
                                respond(
                                    request, 401, "Unauthorized",
                                    "WWW-Authenticate: Digest realm=\"carrier.example\", " +
                                        "nonce=\"bm9uY2UxMjM0NTY=\", qop=auth, algorithm=MD5\r\n",
                                )
                            } else {
                                respond(request, 200, "OK")
                                if (request.joinToString("\n").contains("expires=0")) break
                            }
                        }
                    }
                } catch (e: Throwable) {
                    error.set(e.toString())
                }
                done.countDown()
            }.apply { isDaemon = true; start() }
        }
    }

    @Test
    fun akaChallengeRegistrationAndRestart() = runBlocking {
        // --- First start: 401 -> AKA digest REGISTER -> 200. ---
        val server1 = ServerSocket(5060)
        val script1 = PcscfScript(challengeFirst = true)
        script1.serve(server1)
        assertTrue(RcsDirectSip.start(context, 1), "start failed: ${script1.requests}")
        assertTrue(RcsDirectSip.isRegistered())
        RcsDirectSip.stop()
        assertFalse(RcsDirectSip.isRegistered())
        assertTrue(script1.done.await(20, TimeUnit.SECONDS), "script1: ${script1.error.get()}")
        server1.close()
        assertEqualsRequests(script1)

        // --- Restart after stop: the stack must come back (scope fix). ---
        val server2 = ServerSocket(5060)
        val script2 = PcscfScript(challengeFirst = false)
        script2.serve(server2)
        assertTrue(RcsDirectSip.start(context, 1), "restart failed: ${script2.requests}")
        assertTrue(RcsDirectSip.isRegistered())
        RcsDirectSip.stop()
        assertTrue(script2.done.await(20, TimeUnit.SECONDS), "script2: ${script2.error.get()}")
        server2.close()
        assertTrue(script2.requests.first().contains("REGISTER sip:127.0.0.1"))
        assertTrue(script2.requests.last().contains("expires=0"))
        Unit
    }

    private fun assertEqualsRequests(script: PcscfScript) {
        val all = script.requests
        assertTrue(all.size >= 3, "requests=$all")
        assertTrue(all[0].contains("CSeq: 1 REGISTER"), all[0])
        val authed = all[1]
        assertTrue(authed.contains("Authorization: Digest "), authed)
        assertTrue(authed.contains("username="), authed)
        assertTrue(authed.contains("nonce=\"bm9uY2UxMjM0NTY=\""), authed)
        assertTrue(all.last().contains("expires=0"), all.last())
    }
}
