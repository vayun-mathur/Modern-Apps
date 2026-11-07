package com.vayunmathur.communicate.rcs

import android.content.Context
import android.net.ConnectivityManager
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.rcs.RcsCapabilityExchange
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsDirectSip
import com.vayunmathur.communicate.data.rcs.RcsFileTransfer
import com.vayunmathur.communicate.data.rcs.RcsFileTransferHttp
import com.vayunmathur.communicate.data.rcs.RcsGbaBootstrap
import com.vayunmathur.communicate.data.rcs.RcsHiddenApi
import com.vayunmathur.communicate.data.rcs.RcsImsNetwork
import com.vayunmathur.communicate.data.rcs.RcsMsrpListen
import com.vayunmathur.communicate.data.rcs.RcsOutbox
import com.vayunmathur.communicate.data.rcs.RcsProvisioning
import com.vayunmathur.communicate.data.rcs.RcsProvisioningWatcher
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.RcsTyping
import com.vayunmathur.communicate.data.rcs.RcsTypingThrottle
import com.vayunmathur.communicate.data.rcs.RcsUnavailableReason
import com.vayunmathur.communicate.data.rcs.cacheInboundRcsWithImdn
import com.vayunmathur.communicate.data.rcs.sendRcsDisplayReports
import com.vayunmathur.communicate.data.CommunicateAttachment
import java.net.ServerSocket
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
 * Slice-3 pass 2: fail-closed transport/provisioning paths under Robolectric
 * (no IMS PDN, no carrier privilege, no delegate), plus DB-backed outbox,
 * typing and inbound-cache behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RcsSlice3Pass2ConnectivityShadow::class])
class RcsSlice3Pass2TransportTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- reflectively-built SipMessage bridge (framework class is hidden) ---

    private fun sipMessage(startLine: String, headers: String, body: ByteArray): Any {
        val cls = Class.forName("android.telephony.ims.SipMessage")
        return cls.getConstructor(String::class.java, String::class.java, ByteArray::class.java)
            .newInstance(startLine, headers, body)
    }

    private fun responseMessage(
        startLine: String,
        branch: String = "z9hG4bKpass2",
        callId: String = "pass2-call@rcs",
        cseq: String = "1 MESSAGE",
        extra: String = "",
        body: String = "",
    ): Any {
        val headers = buildString {
            append("Via: SIP/2.0/TCP 10.0.0.1;branch=$branch\r\n")
            append("To: <sip:peer@rcs>;tag=remote\r\n")
            append("From: <sip:me@rcs>;tag=local\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseq\r\n")
            append(extra)
        }
        return sipMessage(startLine, headers, body.toByteArray(Charsets.UTF_8))
    }

    private suspend fun invokeOnSipResponse(message: Any): Boolean {
        val cls = Class.forName("android.telephony.ims.SipMessage")
        val method = RcsSipTransport::class.java.methods.first {
            it.name == "onSipResponseMessage" && it.parameterTypes[0] == cls
        }
        return suspendCoroutine<Any?> { cont ->
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(RcsSipTransport, message, cont as Continuation<Any?>)
            if (result !== COROUTINE_SUSPENDED) cont.resumeWith(Result.success(result))
        } as Boolean
    }

    // --- RcsSipTransport builders / state ---

    @Test
    fun cpimBodyCarriesImdnEnvelopeAndUtf8Length() {
        val body = RcsSipTransport.buildCpimBody("héllo ✉")
        assertTrue(body.contains("NS: imdn <urn:ietf:params:imdn>\r\n"))
        assertTrue(body.contains("imdn.Message-ID: rcs-"))
        assertTrue(body.contains("imdn.Disposition-Notification: positive-delivery, display\r\n"))
        assertTrue(body.contains("Content-Type: text/plain;charset=UTF-8\r\n"))
        val utf8 = "héllo ✉".toByteArray(Charsets.UTF_8).size
        assertTrue(body.contains("Content-Length: $utf8\r\n"), body)
        assertTrue(body.endsWith("héllo ✉"))
    }

    @Test
    fun chatMessageBuildsPagerModeMessage() {
        val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs", toUri = "sip:+1555@rcs", callId = "pass2-chat@rcs", body = "hello",
        )
        assertEquals("MESSAGE sip:+1555@rcs SIP/2.0", startLine)
        assertTrue(headers.contains("Via: SIP/2.0/TCP sip:me@rcs;branch=z9hG4bK"))
        assertTrue(headers.contains("From: <sip:me@rcs>;tag="))
        assertTrue(headers.contains("To: <sip:+1555@rcs>\r\n"))
        assertTrue(headers.contains("Call-ID: pass2-chat@rcs\r\n"))
        assertTrue(headers.contains("CSeq: 1 MESSAGE\r\n"))
        assertTrue(headers.contains("Content-Type: message/cpim\r\n"))
        assertTrue(headers.contains("Content-Length: ${content.size}\r\n"))
        assertTrue(content.toString(Charsets.UTF_8).endsWith("hello"))
    }

    @Test
    fun transportStateWithoutDelegate() {
        RcsSipTransport.tearDown(context)
        assertFalse(RcsSipTransport.canSend())
        assertEquals("none", RcsSipTransport.activeLeg)
        assertNull(RcsSipTransport.lastConfigSnapshot())
        RcsSipTransport.cleanupSession("some-call")
        RcsSipTransport.cleanupSession("")
        assertEquals(5, RcsSipTransport.CPM_FEATURE_TAGS.size)
        assertTrue(RcsSipTransport.CPM_FEATURE_TAGS.any { it.contains("cpm.session") })
        assertTrue(RcsSipTransport.CPM_FEATURE_TAGS.any { it.contains("fthttp") })
    }

    @Test
    fun sendSipMessageFailsClosedWithoutConnection() = runTest {
        val ok = RcsSipTransport.sendSipMessage("MESSAGE sip:x@rcs SIP/2.0", "Via: x\r\n", ByteArray(0))
        assertFalse(ok)
    }

    @Test
    fun onSipResponseMessageRoutesStrayResponses() = runTest {
        // A request is not a response.
        assertFalse(invokeOnSipResponse(responseMessage("MESSAGE sip:x@rcs SIP/2.0")))
        // Provisional with no pending transaction: not consumed as a final.
        assertFalse(invokeOnSipResponse(responseMessage("SIP/2.0 100 Trying")))
        // 2xx to MESSAGE with no pending send: stray, not consumed.
        assertFalse(invokeOnSipResponse(responseMessage("SIP/2.0 200 OK", cseq = "1 MESSAGE")))
        // Failure with no pending send: forwarded to the session layer, consumed.
        assertTrue(invokeOnSipResponse(responseMessage("SIP/2.0 404 Not Found", cseq = "1 MESSAGE")))
        // 2xx to INVITE: dialog completion path, consumed even when stray.
        assertTrue(invokeOnSipResponse(responseMessage("SIP/2.0 200 OK", cseq = "1 INVITE")))
        // Dialog-error codes on a stray response are consumed too.
        assertTrue(invokeOnSipResponse(responseMessage("SIP/2.0 481 Call Leg Does Not Exist", cseq = "1 BYE")))
    }

    @Test
    fun ensureRegisteredReportsNoSubscriptionUnderRobolectric() {
        RcsSipTransport.tearDown(context)
        RcsSipTransport.ensureRegistered(context)
        // The registration coroutine runs on Dispatchers.IO; poll the state.
        val deadline = System.currentTimeMillis() + 10_000
        var state = RcsSipTransport.state.value
        while (System.currentTimeMillis() < deadline) {
            state = RcsSipTransport.state.value
            if (state is RcsRegistrationState.Unavailable || state is RcsRegistrationState.Disabled) break
            Thread.sleep(25)
        }
        assertTrue(
            state is RcsRegistrationState.Unavailable || state is RcsRegistrationState.Disabled,
            "state=$state",
        )
        if (state is RcsRegistrationState.Unavailable) {
            assertTrue(
                state.reason in listOf(
                    RcsUnavailableReason.NoSubscription, RcsUnavailableReason.NotSupported,
                    RcsUnavailableReason.ServiceUnavailable, RcsUnavailableReason.Unknown,
                    RcsUnavailableReason.NoCarrierPrivilege,
                ),
                "reason=${state.reason}",
            )
        }
        RcsSipTransport.tearDown(context)
    }

    // --- RcsProvisioning ---

    @Test
    fun provisioningProbeWithInvalidSubscription() = runTest {
        RcsProvisioning.reset()
        assertEquals(RcsRegistrationState.Unknown, RcsProvisioning.state.value)
        assertNull(RcsProvisioning.lastConfigServerUrl)
        val state = RcsProvisioning.probe(context, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        assertEquals(
            RcsRegistrationState.Unavailable(RcsUnavailableReason.NoSubscription), state,
        )
        assertFalse(RcsProvisioning.hasPhoneStatePermission(context))
        assertEquals(
            context.packageManager.hasSystemFeature("android.hardware.telephony.ims.singlereg"),
            RcsProvisioning.deviceSupportsSingleReg(context),
        )
    }

    @Test
    fun entitlementCheckRejectsBlankAndUnreachable() = runTest {
        assertFalse(RcsProvisioning.entitlementCheck(""))
        assertFalse(RcsProvisioning.entitlementCheck("http://127.0.0.1:1/entitlement"))
    }

    // --- RcsCapabilityExchange / RcsHiddenApi ---

    @Test
    fun capabilityExchangeFailsClosed() = runTest {
        assertFalse(RcsCapabilityExchange.isContactRcsCapable(context, ""))
        assertFalse(RcsCapabilityExchange.isContactRcsCapable(context, "+15551234567"))
        assertEquals(emptyList(), RcsCapabilityExchange.filterCapable(context, emptyList()))
        assertEquals(emptyList(), RcsCapabilityExchange.filterCapable(context, listOf("+15551234567")))
        assertTrue(RcsCapabilityExchange.updates.value.isEmpty())
    }

    class FakeSingleRegManager(private val answer: Any?) {
        fun isRcsVolteSingleRegistrationCapable(): Any? = answer
    }

    @Test
    fun hiddenApiBridgesDegradeGracefully() {
        // Reflective: the return type is a hidden-API stub class that the
        // test compile classpath cannot name directly.
        val sipDelegateManagerMethod = RcsHiddenApi::class.java.methods
            .first { it.name == "sipDelegateManager" }
        // Robolectric's ImsManager shadow answers with a real manager object.
        assertNotNull(sipDelegateManagerMethod.invoke(RcsHiddenApi, context, 1))
        assertEquals(true, RcsHiddenApi.isSingleRegCapable(FakeSingleRegManager(true)))
        assertEquals(false, RcsHiddenApi.isSingleRegCapable(FakeSingleRegManager(false)))
        assertNull(RcsHiddenApi.isSingleRegCapable(FakeSingleRegManager("yes")))
        assertNull(RcsHiddenApi.isSingleRegCapable(Any()))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val callback = object : RcsHiddenApi.UceCallback {
            override fun onCapabilitiesReceived(caps: List<*>) = Unit
            override fun onComplete() = Unit
            override fun onError(errorCode: Int, retryAfterMillis: Long) = Unit
        }
        assertFalse(RcsHiddenApi.requestUceCapabilities(Any(), emptyList(), executor, callback))
        assertFalse(
            RcsHiddenApi.requestUceAvailability(
                Any(), android.net.Uri.parse("tel:+1555"), executor, callback,
            ),
        )
        assertFalse(
            RcsHiddenApi.registerProvisioningCallback(
                Any(), executor, "6.0", "UP_2.0",
                object : RcsHiddenApi.ProvisioningCallback {
                    override fun onConfigurationChanged(configXml: ByteArray) = Unit
                    override fun onConfigurationReset() = Unit
                    override fun onRemoved() = Unit
                },
            ),
        )
        RcsHiddenApi.unregisterProvisioningCallback(Any())
        executor.shutdown()
    }

    @Test
    fun provisioningWatcherRejectsInvalidAndUnprivileged() {
        RcsProvisioningWatcher.watch(context, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        RcsProvisioningWatcher.unwatch()
        RcsProvisioningWatcher.unwatch()
        // A syntactically valid subId without privilege: watch registers
        // nothing observable here, and unwatch still cleans up.
        RcsProvisioningWatcher.watch(context, 4242)
        RcsProvisioningWatcher.unwatch()
    }

    @Test
    fun gbaBootstrapRejectsBlankUrl() = runTest {
        assertNull(RcsGbaBootstrap.bootstrap(context, "", force = false))
        assertNull(RcsGbaBootstrap.bootstrap(context, "   ", force = true))
    }

    // --- RcsImsNetwork / listen / direct SIP fail-closed ---

    @Test
    fun imsNetworkUnavailableAndPlainSocketFallbackWorks() = runTest {
        RcsImsNetwork.reset()
        assertNull(RcsImsNetwork.lastLocalIp)
        assertNull(RcsImsNetwork.imsNetwork(context, SubscriptionManager.INVALID_SUBSCRIPTION_ID))
        assertNull(RcsImsNetwork.listenSocket(context))

        val server = ServerSocket(0)
        val acceptThread = Thread {
            runCatching { server.accept().use { it.getOutputStream().write(7) } }
        }
        acceptThread.isDaemon = true
        acceptThread.start()
        val socket = RcsImsNetwork.createSocket(context, "127.0.0.1", server.localPort, 5_000)
        assertNotNull(socket)
        assertTrue(socket.isConnected)
        assertEquals(7, socket.getInputStream().read())
        socket.close()
        server.close()

        val networks = context.getSystemService(ConnectivityManager::class.java).allNetworks
        if (networks.isNotEmpty()) {
            val ips = RcsImsNetwork.localIps(context, networks[0])
            assertTrue(ips.all { !it.startsWith("127.") })
        }
        RcsImsNetwork.reset()
    }

    @Test
    fun listenSocketDescriptorFormatsPath() {
        val server = ServerSocket(0)
        val listen = RcsImsNetwork.ListenSocket(server, "10.0.0.2", server.localPort)
        assertEquals("msrp://10.0.0.2:${server.localPort}/sess-1;tcp", listen.msrpPath("sess-1"))
        listen.close()
        assertTrue(server.isClosed)
    }

    @Test
    fun msrpListenFailsClosedWithoutIms() = runTest {
        RcsMsrpListen.stop()
        assertFalse(RcsMsrpListen.isListening())
        assertNull(RcsMsrpListen.listenIp())
        assertNull(RcsMsrpListen.listenPort())
        assertFalse(RcsMsrpListen.ensureListening(context) { _, _, _ -> })
        assertNull(RcsMsrpListen.advertisePath(context, "conv-pass2") { _, _, _ -> })
        RcsMsrpListen.notePeerFingerprint("conv-pass2", "AA:BB")
        RcsMsrpListen.dropPending("conv-pass2")
        RcsMsrpListen.stop()
    }

    @Test
    fun directSipFailsClosedWithoutImsNetwork() = runTest {
        assertFalse(RcsDirectSip.isRegistered())
        assertFalse(RcsDirectSip.start(context, SubscriptionManager.INVALID_SUBSCRIPTION_ID))
        assertFalse(
            RcsDirectSip.sendMessage("MESSAGE sip:x@rcs SIP/2.0", "Via: x\r\n", ByteArray(0)),
        )
        RcsDirectSip.onInboundMessage = null
        RcsDirectSip.stop()
        assertFalse(RcsDirectSip.isRegistered())
    }

    // --- Outbox / typing / inbound cache (Room-backed) ---

    private lateinit var testDb: RcsDatabase

    @kotlin.test.BeforeTest
    fun installInMemoryDb() {
        testDb = androidx.room3.Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = testDb
    }

    @kotlin.test.AfterTest
    fun removeInMemoryDb() {
        RcsDatabase.overrideForTest = null
        testDb.close()
    }

    private fun db() = testDb

    @Test
    fun outboxEnqueueKindsAndPendingCount() = runTest {
        db().outboxDao().deleteAll()
        assertEquals(0, RcsOutbox.pendingCount(context))
        assertNull(RcsOutbox.enqueue(context, "conv-pass2", "", emptyList()))
        val textId = RcsOutbox.enqueue(context, "conv-pass2", "hello")
        assertNotNull(textId)
        val row = db().outboxDao().get(textId)
        assertNotNull(row)
        assertEquals("text", row.kind)
        assertEquals(0, row.attempts)
        assertTrue(row.nextRetryMs > row.createdAt)

        val fileId = RcsOutbox.enqueue(
            context, "conv-pass2", "caption",
            listOf(CommunicateAttachment(contentUri = "content://a/1", mimeType = "image/png")),
        )
        assertEquals("file", db().outboxDao().get(fileId!!)?.kind)
        assertEquals("content://a/1", db().outboxDao().get(fileId)?.attachments)

        val groupId = RcsOutbox.enqueue(context, "conv-pass2", "hi all", isGroup = true)
        assertEquals("group", db().outboxDao().get(groupId!!)?.kind)
        assertEquals(3, RcsOutbox.pendingCount(context))
        db().outboxDao().deleteAll()
        assertEquals(0, RcsOutbox.pendingCount(context))
    }

    @Test
    fun outboxPumpExpiresOldRowsAndRetriesDueRows() = runTest {
        db().outboxDao().deleteAll()
        db().cachedMessageDao().deleteAll()
        val now = System.currentTimeMillis()
        // Expired row: deleted and surfaced as a failed cached message.
        db().outboxDao().upsert(
            com.vayunmathur.communicate.data.rcs.RcsOutboxMessage(
                messageId = "pass2-expired", conversationId = "conv-pass2", kind = "text",
                body = "too late", nextRetryMs = now - 1000, expiryMs = now - 1, createdAt = now - 2000,
            ),
        )
        // Max-attempts row with blank body: expired marker uses the placeholder.
        db().outboxDao().upsert(
            com.vayunmathur.communicate.data.rcs.RcsOutboxMessage(
                messageId = "pass2-maxed", conversationId = "conv-pass2", kind = "file",
                body = "", attachments = "content://a/9", attempts = 12,
                nextRetryMs = now - 1000, expiryMs = now + 60_000, createdAt = now - 2000,
            ),
        )
        // Due, unexpired row: the send path fails closed (no transport), so
        // attempts bump with backoff and the row survives.
        db().outboxDao().upsert(
            com.vayunmathur.communicate.data.rcs.RcsOutboxMessage(
                messageId = "pass2-due", conversationId = "conv-pass2", kind = "text",
                body = "retry me", nextRetryMs = now - 1000, expiryMs = now + 60_000,
                createdAt = now - 2000,
            ),
        )
        // Not-yet-due row is untouched.
        db().outboxDao().upsert(
            com.vayunmathur.communicate.data.rcs.RcsOutboxMessage(
                messageId = "pass2-future", conversationId = "conv-pass2", kind = "text",
                body = "later", nextRetryMs = now + 60_000, expiryMs = now + 120_000,
                createdAt = now,
            ),
        )
        val (retried, sent) = RcsOutbox.pumpDue(context, CommunicateRepository)
        assertEquals(3, retried)
        assertEquals(0, sent)
        assertNull(db().outboxDao().get("pass2-expired"))
        assertNull(db().outboxDao().get("pass2-maxed"))
        val failedRow = db().cachedMessageDao().get("pass2-expired")
        assertNotNull(failedRow)
        assertEquals(4, failedRow.status)
        assertTrue(failedRow.outgoing)
        val maxedRow = db().cachedMessageDao().get("pass2-maxed")
        assertEquals("[expired]", maxedRow?.body)
        val due = db().outboxDao().get("pass2-due")
        assertNotNull(due)
        assertEquals(1, due.attempts)
        assertTrue(due.nextRetryMs > now)
        assertNotNull(db().outboxDao().get("pass2-future"))
        db().outboxDao().deleteAll()
        db().cachedMessageDao().deleteAll()
    }

    @Test
    fun typingSendsFailClosedAndThrottleTracksDrafts() = runTest {
        assertFalse(RcsTyping.sendComposing("", true))
        assertFalse(RcsTyping.sendComposing("+15551234567", true))
        assertFalse(RcsTyping.sendComposing("+15551234567", false))
        assertEquals(5_000L, RcsTyping.IDLE_AFTER_MS)
        assertEquals(10_000L, RcsTyping.ACTIVE_THROTTLE_MS)

        val peer = "+15559990000"
        RcsTypingThrottle.clear(peer)
        RcsTypingThrottle.onDraftChanged("", "hello")
        RcsTypingThrottle.onDraftChanged(peer, "")
        RcsTypingThrottle.onDraftChanged(peer, "h")
        RcsTypingThrottle.onDraftChanged(peer, "he")
        RcsTypingThrottle.onDraftChanged(peer, "")
        RcsTypingThrottle.clear(peer)
        RcsTypingThrottle.onDraftChanged(peer, "")
    }

    @Test
    fun inboundCacheWithImdnAndDisplayReportsGate() = runTest {
        db().cachedMessageDao().deleteAll()
        db().conversationDao().deleteAll()
        with(CommunicateRepository) {
            cacheInboundRcsWithImdn(
                context = context,
                conversationId = "conv-pass2",
                body = "inbound hello",
                senderId = "+15550001111",
                messageId = "pass2-in-1",
                imdnId = "imdn-pass2-1",
                ftUrl = "https://files.example/a.png",
                ftMime = "image/png",
            )
        }
        val row = db().cachedMessageDao().get("pass2-in-1")
        assertNotNull(row)
        assertFalse(row.outgoing)
        assertEquals("imdn-pass2-1", row.imdnId)
        assertEquals("https://files.example/a.png", row.ftUrl)
        val conv = db().conversationDao().getConversation("conv-pass2")
        assertNotNull(conv)
        assertEquals(1, conv.unreadCount)

        // A second inbound row bumps the unread count again.
        with(CommunicateRepository) {
            cacheInboundRcsWithImdn(
                context = context, conversationId = "conv-pass2", body = "again",
                messageId = "pass2-in-2", imdnId = "imdn-pass2-2",
            )
        }
        assertEquals(2, db().conversationDao().getConversation("conv-pass2")?.unreadCount)
        assertEquals(2, db().cachedMessageDao().getForConversation("conv-pass2").size)

        // Display reports need a live transport; without one nothing is sent
        // and nothing throws.
        with(CommunicateRepository) {
            sendRcsDisplayReports(context, "conv-pass2")
        }
        assertEquals(2, db().cachedMessageDao().getForConversation("conv-pass2").size)
        db().cachedMessageDao().deleteAll()
        db().conversationDao().deleteAll()
    }

    @Test
    fun inboundCacheRedeliveryDoesNotDoubleCountUnread() = runTest {
        db().cachedMessageDao().deleteAll()
        db().conversationDao().deleteAll()
        // The same SIP messageId delivered twice (retransmit / offline
        // replay) must stay one row with an unread count of one.
        repeat(2) {
            with(CommunicateRepository) {
                cacheInboundRcsWithImdn(
                    context = context,
                    conversationId = "conv-dup",
                    body = "hello",
                    messageId = "dup-1",
                    imdnId = "imdn-dup-1",
                )
            }
        }
        assertEquals(1, db().cachedMessageDao().getForConversation("conv-dup").size)
        assertEquals(1, db().conversationDao().getConversation("conv-dup")?.unreadCount)
        db().cachedMessageDao().deleteAll()
        db().conversationDao().deleteAll()
    }

    // --- File transfer fail-closed ---

    @Test
    fun fileTransferFailsClosedWithoutTransport() = runTest {
        val attachment = CommunicateAttachment(contentUri = "content://a/1", mimeType = "image/png", fileName = "a.png")
        assertFalse(
            RcsFileTransfer.sendFiles(context, CommunicateRepository, "", "hi", listOf(attachment)),
        )
        assertFalse(
            RcsFileTransfer.sendFiles(context, CommunicateRepository, "+1555", "hi", emptyList()),
        )
        assertFalse(
            RcsFileTransfer.sendFiles(context, CommunicateRepository, "+1555", "hi", listOf(attachment)),
        )
    }

    @Test
    fun ftHttpUploadDownloadFailClosed() = runTest {
        val saved = RcsFileTransferHttp.contentServerUri
        try {
            RcsFileTransferHttp.contentServerUri = null
            val attachment = CommunicateAttachment(contentUri = "content://a/1", mimeType = "image/png")
            assertNull(RcsFileTransferHttp.upload(context, attachment))
            assertNull(RcsFileTransferHttp.download(context, ""))
            assertFalse(
                RcsFileTransferHttp.sendFile(context, CommunicateRepository, "+1555", attachment, "cap"),
            )
            // With a server URI but an unreadable attachment, upload still fails.
            RcsFileTransferHttp.contentServerUri = "http://127.0.0.1:1/ft"
            assertNull(RcsFileTransferHttp.upload(context, attachment))
        } finally {
            RcsFileTransferHttp.contentServerUri = saved
        }
    }
}
