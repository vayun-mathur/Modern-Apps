package com.vayunmathur.communicate.rcs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.rcs.MsrpSetup
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFileTransferHttp
import com.vayunmathur.communicate.data.rcs.RcsMsrpFraming
import com.vayunmathur.communicate.data.rcs.RcsSession
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-3 pass 2: FT-over-HTTP against a real loopback HTTP server (digest
 * challenge → multipart upload → download URL), FT-over-MSRP against a
 * loopback MSRP peer, and the full sendFile chain over the fake SIP sender.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RcsSlice3Pass2ConnectivityShadow::class])
class RcsSlice3Pass2FtNetworkTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var testDb: RcsDatabase
    private var http: HttpServer? = null
    private val sent = CopyOnWriteArrayList<Triple<String, String, ByteArray>>()

    @BeforeTest
    fun setUp() {
        testDb = androidx.room3.Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = testDb
        sent.clear()
        RcsSipTransport.fakeSenderForTest = { s, h, b -> sent += Triple(s, h, b.copyOf()); true }
    }

    @AfterTest
    fun tearDown() {
        http?.stop(0)
        http = null
        RcsFileTransferHttp.contentServerUri = null
        RcsSipTransport.fakeSenderForTest = null
        RcsSessionManager.sessionsMutable.value = emptyMap()
        RcsDatabase.overrideForTest = null
        testDb.close()
    }

    private fun attachmentFor(bytes: ByteArray, name: String = "photo.png"): CommunicateAttachment {
        val file = File(context.cacheDir, name).apply { writeBytes(bytes) }
        return CommunicateAttachment(
            contentUri = android.net.Uri.fromFile(file).toString(),
            mimeType = "image/png", fileName = name,
        )
    }

    private fun startHttp(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        http = server
        return "http://127.0.0.1:${server.address.port}/ft"
    }

    private fun digestChallenge(exchange: com.sun.net.httpserver.HttpExchange) {
        exchange.responseHeaders.set(
            "WWW-Authenticate",
            "Digest realm=\"rcs\", nonce=\"abc123nonce\", qop=auth",
        )
        exchange.sendResponseHeaders(401, -1)
        exchange.close()
    }

    @Test
    fun uploadHarvestsChallengeAndPostsMultipart() = runTest {
        val bodies = CopyOnWriteArrayList<ByteArray>()
        val authHeaders = CopyOnWriteArrayList<String>()
        val url = startHttp { exchange ->
            val body = exchange.requestBody.readAllBytes()
            if (exchange.requestHeaders.getFirst("Authorization") == null) {
                digestChallenge(exchange)
            } else {
                bodies += body
                authHeaders += exchange.requestHeaders.getFirst("Authorization")
                val payload = "https://files.example/dl/abc123".toByteArray()
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.write(payload)
                exchange.close()
            }
        }
        RcsFileTransferHttp.contentServerUri = url
        val attachment = attachmentFor(ByteArray(64) { it.toByte() })
        val downloadUrl = RcsFileTransferHttp.upload(context, attachment)
        assertEquals("https://files.example/dl/abc123", downloadUrl)
        assertEquals(1, bodies.size)
        val multipart = bodies.first().toString(Charsets.ISO_8859_1)
        assertTrue(multipart.contains("name=\"tid\""), multipart.take(300))
        assertTrue(multipart.contains("filename=\"photo.png\""), multipart.take(400))
        assertTrue(authHeaders.first().startsWith("Digest username="), authHeaders.first())
        assertTrue(authHeaders.first().contains("nonce=\"abc123nonce\""))
    }

    @Test
    fun uploadUsesInjectedGbaCredentialsWhenPresent() = runTest {
        val authHeaders = CopyOnWriteArrayList<String>()
        val url = startHttp { exchange ->
            exchange.requestBody.readAllBytes()
            val auth = exchange.requestHeaders.getFirst("Authorization")
            if (auth == null) {
                digestChallenge(exchange)
            } else {
                authHeaders += auth
                val payload = "https://files.example/dl/gba1".toByteArray()
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.write(payload)
                exchange.close()
            }
        }
        RcsFileTransferHttp.contentServerUri = url
        com.vayunmathur.communicate.data.rcs.RcsGbaAuth.injected =
            com.vayunmathur.communicate.data.rcs.RcsGbaAuth.GbaCredentials("bt-user-1", byteArrayOf(5, 6, 7))
        try {
            val downloadUrl = RcsFileTransferHttp.upload(context, attachmentFor(ByteArray(24) { 2 }))
            assertEquals("https://files.example/dl/gba1", downloadUrl)
            assertTrue(authHeaders.first().contains("username=\"bt-user-1\""), authHeaders.first())
        } finally {
            com.vayunmathur.communicate.data.rcs.RcsGbaAuth.injected = null
        }
    }

    @Test
    fun uploadRetryAfterSecond401GivesUp() = runTest {
        var requests = 0
        val url = startHttp { exchange ->
            requests++
            exchange.requestBody.readAllBytes()
            // Every POST is challenged, including the authenticated retry.
            digestChallenge(exchange)
        }
        RcsFileTransferHttp.contentServerUri = url
        val attachment = attachmentFor(ByteArray(16) { 1 })
        assertNull(RcsFileTransferHttp.upload(context, attachment))
        assertTrue(requests >= 2, "requests=$requests")
    }

    @Test
    fun uploadWithoutChallengeReturnsNull() = runTest {
        val url = startHttp { exchange ->
            exchange.requestBody.readAllBytes()
            val payload = "no challenge here".toByteArray()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.write(payload)
            exchange.close()
        }
        RcsFileTransferHttp.contentServerUri = url
        assertNull(RcsFileTransferHttp.upload(context, attachmentFor(ByteArray(8))))
    }

    @Test
    fun downloadFetchesBytesToCacheFile() = runTest {
        val payload = ByteArray(128) { (255 - it).toByte() }
        val url = startHttp { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.write(payload)
            exchange.close()
        }
        val file = RcsFileTransferHttp.download(context, url)
        assertNotNull(file)
        assertEquals(payload.toList(), file.readBytes().toList())
        // A 404 download is rejected.
        val missing = startHttp { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        assertNull(RcsFileTransferHttp.download(context, missing))
    }

    @Test
    fun sendFileOverHttpPathCachesAndSends() = runTest {
        val url = startHttp { exchange ->
            val body = exchange.requestBody.readAllBytes()
            if (exchange.requestHeaders.getFirst("Authorization") == null) {
                digestChallenge(exchange)
            } else {
                body.size
                val payload = "<resp>https://files.example/dl/send1</resp>".toByteArray()
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.write(payload)
                exchange.close()
            }
        }
        RcsFileTransferHttp.contentServerUri = url
        val attachment = attachmentFor(ByteArray(32) { 3 }, "send.png")
        assertTrue(RcsFileTransferHttp.sendFile(context, CommunicateRepository, "+15556001", attachment, "caption here"))
        val ftMsg = sent.first { it.first.startsWith("MESSAGE ") }
        val wire = ftMsg.third.toString(Charsets.UTF_8)
        assertTrue(wire.contains("File-URL: https://files.example/dl/send1"), wire)
        val rows = testDb.cachedMessageDao().getForConversation("+15556001")
        assertEquals(1, rows.size)
        assertEquals("caption here", rows.first().body)
        assertEquals("https://files.example/dl/send1", rows.first().ftUrl)
    }

    @Test
    fun sendFileOverMsrpSession() = runTest {
        val server = ServerSocket(0)
        val port = server.localPort
        val peerDone = CountDownLatch(1)
        val peerError = java.util.concurrent.atomic.AtomicReference<String>()
        Thread {
            try {
                server.accept().use { s ->
                    s.soTimeout = 20_000
                    val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                    // Two CPIM SENDs (file descriptor chunks + descriptor): ack each frame.
                    repeat(2) {
                        // Read head.
                        val head = mutableListOf<String>()
                        var line = input.readLine()
                        var guard = 0
                        while (line != null && line.isNotEmpty() && guard++ < 32) {
                            head += line
                            line = input.readLine()
                        }
                        val txid = head.firstOrNull()?.split(" ")?.getOrNull(1).orEmpty()
                        // Consume body lines to the end marker.
                        while (true) {
                            val l = input.readLine() ?: break
                            if (l.startsWith("-------$txid")) break
                        }
                        val out = s.getOutputStream()
                        out.write(RcsMsrpFraming.buildErrorResponse("", txid, 200, "OK"))
                        out.flush()
                    }
                }
            } catch (e: Throwable) {
                peerError.set(e.toString())
            }
            peerDone.countDown()
        }.apply { isDaemon = true; start() }

        val recipient = "+15556002"
        RcsSessionManager.sessionsMutable.value = mapOf(
            recipient to RcsSession(
                dialogId = "ft-d", callId = "ft-call-1", localTag = "lt", remoteTag = "rt",
                remoteUri = "sip:peer@rcs", conversationId = recipient,
                msrpLocalPath = "msrp://127.0.0.1:$port/self;tcp",
                msrpRemotePath = "msrp://127.0.0.1:$port/peer;tcp",
                msrpSetup = MsrpSetup.PASSIVE, confirmed = true,
            ),
        )
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val attachment = attachmentFor(png, "msrp.png")
        assertTrue(RcsFileTransferHttp.sendFile(context, CommunicateRepository, recipient, attachment, "via msrp"))
        assertTrue(peerDone.await(20, TimeUnit.SECONDS), "peer=${peerError.get()}")
        assertNull(peerError.get())
        val rows = testDb.cachedMessageDao().getForConversation(recipient)
        assertEquals(1, rows.size)
        assertEquals("msrp:ft-call-1", rows.first().ftUrl)
        assertEquals("via msrp", rows.first().body)
        server.close()
    }
}
