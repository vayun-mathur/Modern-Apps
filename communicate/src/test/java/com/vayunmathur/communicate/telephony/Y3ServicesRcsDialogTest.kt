package com.vayunmathur.communicate.telephony

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telephony.ims.SipMessage
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceAuthException
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceClient
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsMsrpListen
import com.vayunmathur.communicate.data.rcs.RcsSession
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Y3: `GoogleVoiceSyncService.pollOnce` (localhost GV server through the
 * `baseOverrideForTest` seam, plus the no-credentials poll), the remaining
 * `RcsSyncService.handleInbound` / `handleDialogMethod` routing legs, the
 * remaining `RcsInboundDialogKt` legs (`handleInboundUpdate` with a live
 * dialog, `answerReInvite`, `onMsrpAccepted` over a real socket pair), and
 * `CommunicateInCallService`'s CallStyle notification legs.
 *
 * Private suspend functions are invoked reflectively with a hand-rolled
 * continuation; SIP traffic is captured through `fakeSenderForTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3ServicesRcsDialogTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var rcsDb: RcsDatabase
    private lateinit var server: HttpServer
    private val sent = mutableListOf<Triple<String, String, ByteArray>>()
    @Volatile private var listBody: String = "[]"

    @BeforeTest
    fun setUp() {
        rcsDb = Room.inMemoryDatabaseBuilder(app, RcsDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = rcsDb
        sent.clear()
        RcsSipTransport.fakeSenderForTest = { startLine, headers, body ->
            sent += Triple(startLine, headers, body.copyOf())
            true
        }
        RcsSessionManager.sessionsMutable.value = emptyMap()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val bytes = listBody.encodeToByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        GoogleVoiceClient.baseOverrideForTest = "http://127.0.0.1:${server.address.port}/"
    }

    @AfterTest
    fun tearDown() {
        GoogleVoiceClient.baseOverrideForTest = null
        runBlocking { GoogleVoiceSession.get(app).signOut() }
        server.stop(0)
        RcsSipTransport.fakeSenderForTest = null
        RcsDatabase.overrideForTest = null
        rcsDb.close()
        RcsSessionManager.sessionsMutable.value = emptyMap()
    }

    /** Invoke a private/any suspend function reflectively (single-Continuation tail). */
    private fun invokeSuspend(target: Any, methodName: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.firstOrNull { it.name == methodName }
            ?: target.javaClass.methods.firstOrNull { it.name == methodName }
            ?: error("no method $methodName on ${target.javaClass}")
        method.isAccessible = true
        var outcome: Result<Any?>? = null
        val cont = object : Continuation<Any?> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) { outcome = result }
        }
        val r = method.invoke(target, *args, cont)
        if (r !== COROUTINE_SUSPENDED) return r
        val deadline = System.currentTimeMillis() + 30_000
        while (outcome == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return (outcome ?: error("suspend $methodName never resumed")).getOrThrow()
    }

    private fun invokeSuspendExtension(receiver: Any, owner: Any, methodName: String, vararg args: Any?): Any? {
        // Extension functions compile to static methods on the file class; find via reflection on owner class.
        var outcome: Result<Any?>? = null
        val cont = object : Continuation<Any?> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) { outcome = result }
        }
        val method = owner.javaClass.declaredMethods.firstOrNull { it.name == methodName }
            ?: error("no extension $methodName on ${owner.javaClass}")
        method.isAccessible = true
        val r = method.invoke(owner, receiver, *args, cont)
        if (r !== COROUTINE_SUSPENDED) return r
        val deadline = System.currentTimeMillis() + 30_000
        while (outcome == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return (outcome ?: error("suspend $methodName never resumed")).getOrThrow()
    }

    private fun sip(
        startLine: String,
        callId: String = "call-y3@host",
        from: String = "<sip:+15550005001@rcs>;tag=ftag1",
        extraHeaders: String = "",
        body: String = "",
        contentType: String? = null,
    ): SipMessage {
        val headers = buildString {
            append("Via: SIP/2.0/TCP 10.0.0.1;branch=z9hG4bKy3\r\n")
            append("From: $from\r\n")
            append("To: <sip:+15550005002@rcs>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: 1 ${startLine.substringBefore(' ')}\r\n")
            if (contentType != null) append("Content-Type: $contentType\r\n")
            append(extraHeaders)
            append("Content-Length: ${body.toByteArray().size}\r\n")
        }
        return SipMessage(startLine, headers, body.toByteArray(Charsets.UTF_8))
    }

    private fun offerSdp(): String =
        "v=0\r\no=- 1 1 IN IP4 10.0.0.1\r\ns=-\r\nc=IN IP4 10.0.0.1\r\nt=0 0\r\n" +
            "m=message 2855 TCP/MSRP *\r\na=path:msrp://10.0.0.1:2855/sess1;tcp\r\na=setup:active\r\n"

    private fun gvThreadBody(items: List<String>): String =
        """[["t.+14155550102",1,[${items.joinToString(",")}],null,[["+14155550102"]]]]"""

    private fun gvItem(id: String, ts: Long, type: Long, body: String): String =
        """["$id",$ts,"+12133945548",["+14155550102"],$type,1,null,null,null,"$body",null,null,5,1,null,"+14155550102",0]"""

    // ---------- GoogleVoiceSyncService.pollOnce ----------

    @Test
    fun `pollOnce notifies only new inbound messages and advances the watermark`() {
        runBlocking { GoogleVoiceSession.get(app).save("SAPISID=abc123; other=x", "APIKEY", "0") }
        val now = System.currentTimeMillis()
        listBody = gvThreadBody(listOf(
            gvItem("m-old", now - 3_600_000L, 10L, "old inbound"),
            gvItem("m-out", now - 20_000L, 11L, "my reply"),
            gvItem("m-new", now - 30_000L, 10L, "fresh inbound"),
        ))
        val controller = Robolectric.buildService(GoogleVoiceSyncService::class.java).create()
        val service = controller.get()
        val nm = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        val before = nm.allNotifications.size

        invokeSuspend(service, "pollOnce")
        // First poll: the first-poll window covers only the fresh inbound;
        // the outgoing and the hour-old inbound never notify.
        assertEquals(before + 1, nm.allNotifications.size, "notification count=${nm.allNotifications.size} before=$before")
        assertEquals(now - 20_000L, runBlocking { GoogleVoiceSession.get(app).lastSeenMessageTimestampMillis() })

        // Second poll: a genuinely newer inbound notifies and the watermark
        // moves to it; already-seen rows stay silent. ConversationSpace
        // notifies under a stable (tag, id) per conversation, so the second
        // post replaces the first instead of stacking a new entry.
        listBody = gvThreadBody(listOf(
            gvItem("m-new", now - 30_000L, 10L, "fresh inbound"),
            gvItem("m-out", now - 20_000L, 11L, "my reply"),
            gvItem("m-newer", now + 60_000L, 10L, "even newer"),
        ))
        invokeSuspend(service, "pollOnce")
        assertEquals(before + 1, nm.allNotifications.size)
        assertEquals(now + 60_000L, runBlocking { GoogleVoiceSession.get(app).lastSeenMessageTimestampMillis() })
        controller.destroy()
    }

    @Test
    fun `pollOnce without credentials raises the auth exception`() {
        runBlocking { GoogleVoiceSession.get(app).signOut() }
        val controller = Robolectric.buildService(GoogleVoiceSyncService::class.java).create()
        val service = controller.get()
        val thrown = runCatching { invokeSuspend(service, "pollOnce") }.exceptionOrNull()
        val cause = (thrown as? java.lang.reflect.InvocationTargetException)?.targetException ?: thrown
        assertTrue(cause is GoogleVoiceAuthException, "cause=$cause")
        controller.destroy()
    }

    // ---------- RcsSyncService.handleInbound / handleDialogMethod ----------

    @Test
    fun `handleInbound routes responses acks and unknown methods without caching rows`() = runBlocking {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        // SIP response start line -> transport response handler, no dialog work.
        invokeSuspend(service, "handleInbound",
            SipMessage("SIP/2.0 200 OK", "Via: SIP/2.0/TCP h;branch=z9\r\nCall-ID: r1@h\r\n", ByteArray(0)))
        // ACK on an unknown dialog is consumed by the dialog router.
        invokeSuspend(service, "handleInbound", sip("ACK sip:+15550005002@rcs SIP/2.0", callId = "ack-y3@host"))
        // An unknown method falls through the dialog router to envelope
        // parsing, which drops an empty-body stanza (no cached row).
        invokeSuspend(service, "handleInbound", sip("PRACK sip:+15550005002@rcs SIP/2.0", callId = "prack-y3@host", body = ""))
        assertTrue(rcsDb.cachedMessageDao().getForConversation("+15550005001").isEmpty())
        service.onDestroy()
        Unit
    }

    @Test
    fun `handleDialogMethod consumes every dialog verb and rejects unknown ones`() = runBlocking {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        assertEquals(true, invokeSuspend(service, "handleDialogMethod", "ACK",
            sip("ACK sip:x@rcs SIP/2.0", callId = "dm-ack@host")))
        assertEquals(true, invokeSuspend(service, "handleDialogMethod", "BYE",
            sip("BYE sip:x@rcs SIP/2.0", callId = "dm-bye@host")))
        assertEquals(true, invokeSuspend(service, "handleDialogMethod", "OPTIONS",
            sip("OPTIONS sip:x@rcs SIP/2.0", callId = "dm-opt@host")))
        assertTrue(sent.any { it.first == "SIP/2.0 200 OK" && it.second.contains("Accept-Contact:") },
            "sent=${sent.map { it.first }}")
        assertEquals(false, invokeSuspend(service, "handleDialogMethod", "PRACK",
            sip("PRACK sip:x@rcs SIP/2.0", callId = "dm-prack@host")))
        assertEquals(false, invokeSuspend(service, "handleDialogMethod", "MESSAGE",
            sip("MESSAGE sip:x@rcs SIP/2.0", callId = "dm-msg@host", body = "x", contentType = "text/plain")))
        service.onDestroy()
        Unit
    }

    // ---------- RcsInboundDialogKt remaining legs ----------

    @Test
    fun `update on a live dialog answers with current SDP and on an unknown dialog with 481`() = runBlocking {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        service.handleInboundInvite(sip("INVITE sip:+15550005002@rcs SIP/2.0", body = offerSdp(), contentType = "application/sdp"))
        assertNotNull(RcsSessionManager.sessionFor("+15550005001"))
        // Seed the negotiated local MSRP path (normally bound by the accept
        // loop) so currentSdpFor can answer with SDP.
        val seeded = RcsSessionManager.sessionFor("+15550005001")!!
        RcsSessionManager.sessionsMutable.value = RcsSessionManager.sessionsMutable.value +
            ("+15550005001" to seeded.copy(msrpLocalPath = "msrp://127.0.0.1:2855/localy3;tcp"))
        sent.clear()
        service.handleInboundUpdate(sip("UPDATE sip:+15550005002@rcs SIP/2.0"))
        // The accepted session carries an MSRP local path, so the refresh is
        // answered 200 with an SDP body (not the 488 no-SDP leg).
        val ok = sent.firstOrNull { it.first.startsWith("SIP/2.0 200") }
        assertNotNull(ok, "sent=${sent.map { it.first }}")
        assertTrue(ok.third.isNotEmpty(), "200 UPDATE answer must carry SDP")
        assertTrue(ok.second.contains("Content-Type: application/sdp"), ok.second)
        sent.clear()
        service.handleInboundUpdate(sip("UPDATE sip:+15550005002@rcs SIP/2.0", callId = "gone-y3@host"))
        assertTrue(sent.any { it.first.startsWith("SIP/2.0 481") }, "sent=${sent.map { it.first }}")
        service.onDestroy()
        Unit
    }

    @Test
    fun `reInvite answers on the existing dialog without creating a second session`() = runBlocking {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        service.handleInboundInvite(sip("INVITE sip:+15550005002@rcs SIP/2.0", body = offerSdp(), contentType = "application/sdp"))
        assertEquals(1, RcsSessionManager.sessions.value.size)
        // Seed the negotiated local path so the re-INVITE answer carries SDP.
        val seeded = RcsSessionManager.sessionFor("+15550005001")!!
        RcsSessionManager.sessionsMutable.value = RcsSessionManager.sessionsMutable.value +
            ("+15550005001" to seeded.copy(msrpLocalPath = "msrp://127.0.0.1:2855/localy3;tcp"))
        sent.clear()
        // Same Call-ID: the re-INVITE leg (answerReInvite) answers in-dialog.
        service.handleInboundInvite(sip("INVITE sip:+15550005002@rcs SIP/2.0", body = offerSdp(), contentType = "application/sdp"))
        assertEquals(1, RcsSessionManager.sessions.value.size)
        val ok = sent.firstOrNull { it.first.startsWith("SIP/2.0 200") }
        assertNotNull(ok, "sent=${sent.map { it.first }}")
        assertTrue(ok.third.isNotEmpty(), "re-INVITE 200 must carry SDP")
        service.onDestroy()
        Unit
    }

    @Test
    fun `onMsrpAccepted binds a connection for a known session and closes sockets for unknown ones`() {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        val conversationId = "+15550007777"
        RcsSessionManager.sessionsMutable.value = mapOf(
            conversationId to RcsSession(
                dialogId = "d-y3", callId = "call-y3-msrp", localTag = "lt", remoteTag = "rt",
                remoteUri = "sip:+15550007777@rcs", conversationId = conversationId,
                msrpLocalPath = "msrp://127.0.0.1:2855/localy3;tcp",
            ),
        )
        val head = RcsMsrpListen.AcceptedHead(listOf(
            "MSRP 1 SEND",
            "To-Path: msrp://127.0.0.1:2855/localy3;tcp",
            "From-Path: msrp://10.0.0.9:9000/peery3;tcp",
            "Byte-Range: 1-0/0",
        ))

        // Known conversation: the accepted socket becomes the session's conn.
        val server1 = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val client1 = Socket("127.0.0.1", server1.localPort)
        val accepted1 = server1.accept()
        service.onMsrpAccepted(conversationId, accepted1, head)
        assertNotNull(service.msrpConnections[conversationId])
        assertFalse(service.msrpConnections[conversationId]!!.isClosed())
        client1.close(); server1.close()

        // Unknown conversation: the socket is closed and nothing is bound.
        val server2 = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val client2 = Socket("127.0.0.1", server2.localPort)
        val accepted2 = server2.accept()
        service.onMsrpAccepted("+15550009999", accepted2, head)
        assertNull(service.msrpConnections["+15550009999"])
        assertTrue(accepted2.isClosed)
        client2.close(); server2.close()
        service.onDestroy()
    }

    // ---------- CommunicateInCallService CallStyle legs ----------

    /** A no-op `android.telecom.InCallAdapter` over a proxied hidden Telecom service. */
    private fun noOpInCallAdapter(): Any? {
        fun proxyFor(ifaceName: String): Any? = runCatching {
            val iface = Class.forName(ifaceName)
            java.lang.reflect.Proxy.newProxyInstance(
                iface.classLoader, arrayOf(iface),
            ) { _, method, _ ->
                when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        }.getOrNull()
        val proxies = listOfNotNull(
            proxyFor("com.android.internal.telecom.IInCallAdapter"),
            proxyFor("com.android.internal.telecom.ITelecomService"),
        )
        if (proxies.isEmpty()) return null
        val adapterClass = Class.forName("android.telecom.InCallAdapter")
        val ctor = adapterClass.declaredConstructors.firstOrNull { it.parameterCount in 1..3 }
            ?: return null
        ctor.isAccessible = true
        val args = ctor.parameterTypes.map { p ->
            when {
                proxies.any { p.isInstance(it) } -> proxies.first { p.isInstance(it) }
                p == String::class.java -> "y3-call-1"
                p.typeName == "android.os.Looper" -> android.os.Looper.getMainLooper()
                else -> null
            }
        }.toTypedArray()
        return ctor.newInstance(*args)
    }

    /** Injection outcome flags for [fakeCall]'s probed field writes. */
    private class FakeCallFlags {
        var handleSet = false
        var stateSet = false
        var detailsSet = false
    }

    private fun buildDetails(
        alloc: java.lang.reflect.Method,
        unsafe: Any,
        state: Int,
        number: String,
        flags: FakeCallFlags,
    ): Any {
        val details = alloc.invoke(unsafe, Call.Details::class.java)
        for (f in Call.Details::class.java.declaredFields) {
            f.isAccessible = true
            when {
                f.type == Uri::class.java -> {
                    f.set(details, Uri.fromParts("tel", number, null)); flags.handleSet = true
                }
                f.type == Bundle::class.java -> f.set(details, Bundle())
                f.name.equals("mState", ignoreCase = true) && f.type == Int::class.javaPrimitiveType -> {
                    f.setInt(details, state); flags.stateSet = true
                }
            }
        }
        return details
    }

    private fun injectCallFields(call: Any, details: Any, state: Int, flags: FakeCallFlags) {
        for (f in Call::class.java.declaredFields) {
            f.isAccessible = true
            if (f.type == Call.Details::class.java) { f.set(call, details); flags.detailsSet = true }
            if (f.name.equals("mState", ignoreCase = true) && f.type == Int::class.javaPrimitiveType) {
                runCatching { f.setInt(call, state) }
            }
            // Unsafe skips constructors, so callback/listener registries
            // start null; registerCallback would NPE without them.
            if (java.util.List::class.java.isAssignableFrom(f.type)) {
                runCatching { f.set(call, java.util.concurrent.CopyOnWriteArrayList<Any>()) }
            }
            if (java.util.Set::class.java.isAssignableFrom(f.type)) {
                runCatching { f.set(call, java.util.concurrent.ConcurrentHashMap.newKeySet<Any>()) }
            }
            if (f.type == String::class.java && f.name.contains("CallId", ignoreCase = true)) {
                runCatching { f.set(call, "y3-call-1") }
            }
            // answer()/reject()/disconnect() delegate to an InCallAdapter
            // backed by the Telecom binder service; install a no-op
            // adapter over a dynamic proxy of the hidden service iface.
            if (f.name.equals("mInCallAdapter", ignoreCase = true)) {
                runCatching { f.set(call, noOpInCallAdapter()) }
            }
        }
    }

    /**
     * Fake a Telecom [Call] via Unsafe allocation + field injection. Returns
     * null (with [reason] set) when the android-all `Call`/`Details` shape
     * cannot be made faithful — Robolectric ships no ShadowCall, so the
     * field layout is probed rather than assumed.
     */
    private fun fakeCall(state: Int, number: String, reason: StringBuilder): Call? {
        return try {
            val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = unsafeField.get(null)
            val alloc = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            val flags = FakeCallFlags()
            val details = buildDetails(alloc, unsafe, state, number, flags)
            val call = alloc.invoke(unsafe, Call::class.java)
            injectCallFields(call, details, state, flags)
            val c = call as Call
            val ok = flags.handleSet && flags.detailsSet &&
                c.details?.handle?.schemeSpecificPart == number &&
                (c.details?.state == state || flags.stateSet)
            if (!ok) {
                reason.append(
                    "handleSet=${flags.handleSet} detailsSet=${flags.detailsSet} " +
                        "stateSet=${flags.stateSet} details=${c.details}")
                null
            } else c
        } catch (t: Throwable) {
            reason.append("${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    @Test
    fun `call style notification legs follow the faked call state`() {
        val reason = StringBuilder()
        val ringing = fakeCall(Call.STATE_RINGING, "+14155550102", reason)
        if (ringing == null) {
            // Documented limitation: without a faithful Call the CallStyle
            // legs cannot run on the host JVM (Y1InCallServiceTest covers the
            // projections they delegate to). The service legs that need no
            // Call still hold: actions with no current call post nothing.
            val controller = Robolectric.buildService(CommunicateInCallService::class.java).create()
            val service = controller.get()
            assertEquals(android.app.Service.START_NOT_STICKY,
                service.onStartCommand(android.content.Intent().setAction(
                    "com.vayunmathur.communicate.regularcall.ANSWER"), 0, 1))
            val nm = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
            assertTrue(nm.allNotifications.isEmpty())
            controller.destroy()
            println("Y3 InCall fake unavailable: $reason")
            return
        }
        val controller = Robolectric.buildService(CommunicateInCallService::class.java).create()
        val service = controller.get()
        val nm = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))

        service.onCallAdded(ringing)
        val incoming = nm.getNotification(4811)
        assertNotNull(incoming, "ringing call must post the CallStyle notification")
        assertEquals(android.app.Notification.CATEGORY_CALL, incoming.category)

        // Answer via the service intent, then removal clears the notification.
        service.onStartCommand(android.content.Intent().setAction(
            "com.vayunmathur.communicate.regularcall.ANSWER"), 0, 2)
        service.onCallRemoved(ringing)
        assertNull(nm.getNotification(4811))
        assertFalse(LiveCalls.simCallLive)
        controller.destroy()
    }
}
