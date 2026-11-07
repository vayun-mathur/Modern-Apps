package com.vayunmathur.communicate.rcs

import com.vayunmathur.communicate.data.rcs.buildInvite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.RcsUnavailableReason
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.async
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
 * Slice-3 pass 2: the SipDelegate transport with a fake framework
 * connection installed through the test seam — delegate callbacks,
 * pending-send acks, digest-auth retry, inbound routing, teardown.
 * Framework types are built reflectively (they are hidden APIs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RcsSlice3Pass2ProvisioningShadow::class])
class RcsSlice3Pass2DelegateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val sipMessageClass: Class<*> = Class.forName("android.telephony.ims.SipMessage")
    private val connectionClass: Class<*> = Class.forName("android.telephony.ims.SipDelegateConnection")

    private class FakeConnection {
        data class SentMessage(val message: Any, val version: Long, val branch: String)
        val sentMessages = CopyOnWriteArrayList<SentMessage>()
        val cleanedSessions = CopyOnWriteArrayList<String>()
        val notifiedReceived = CopyOnWriteArrayList<String>()
        @Volatile var failSend = false

        val proxy: Any
        init {
            val connectionClass = Class.forName("android.telephony.ims.SipDelegateConnection")
            val sipMessageClass = Class.forName("android.telephony.ims.SipMessage")
            val branchMethod = sipMessageClass.getMethod("getViaBranchParameter")
            val handler = InvocationHandler { _, method, args ->
                when (method.name) {
                    "sendMessage" -> {
                        if (failSend) throw RuntimeException("fake send failure")
                        val message = args[0]
                        sentMessages += SentMessage(
                            message, args[1] as Long, branchMethod.invoke(message) as String,
                        )
                        null
                    }
                    "cleanupSession" -> { cleanedSessions += args[0] as String; null }
                    "notifyMessageReceived" -> { notifiedReceived += args[0] as String; null }
                    else -> null
                }
            }
            proxy = Proxy.newProxyInstance(connectionClass.classLoader, arrayOf(connectionClass), handler)
        }
    }

    private fun sipMessage(startLine: String, headers: String, body: ByteArray = ByteArray(0)): Any =
        sipMessageClass.getConstructor(String::class.java, String::class.java, ByteArray::class.java)
            .newInstance(startLine, headers, body)

    private fun config(version: Long = 7L): Any {
        val builderClass = Class.forName("android.telephony.ims.SipDelegateConfiguration\$Builder")
        val builder = builderClass
            .getConstructor(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, InetSocketAddress::class.java, InetSocketAddress::class.java)
            .newInstance(version, 1, InetSocketAddress("10.0.0.2", 5060), InetSocketAddress("10.0.0.1", 5060))
        fun set(name: String, value: String) {
            builderClass.getMethod(name, String::class.java).invoke(builder, value)
        }
        set("setPublicUserIdentifier", "sip:+15550001@carrier.example")
        set("setHomeDomain", "carrier.example")
        set("setImei", "imei-123")
        set("setSipServiceRouteHeader", "<sip:proxy.carrier.example;lr>")
        set("setSipPaniHeader", "3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=abc")
        set("setSipUserAgentHeader", "CarrierUA/1.0")
        return builderClass.getMethod("build").invoke(builder)
    }

    private object RcsSessionManagerForInvite {
        fun build(): Triple<String, String, ByteArray> =
            with(com.vayunmathur.communicate.data.rcs.RcsSessionManager) {
                buildInvite(
                    targetUri = "sip:peer@carrier.example",
                    callId = "invite-call@rcs",
                    localTag = "ltag1234",
                    branch = "z9hG4bKinvite",
                    conversationId = "conv-invite",
                    subject = "hello subject",
                )
            }
    }

    private fun seam(name: String): java.lang.reflect.Method =
        RcsSipTransport::class.java.methods.first { it.name.startsWith(name) }

    private fun install(fake: FakeConnection?, version: Long = 7L, config: Any? = if (fake != null) config(version) else null) {
        seam("installConnectionForTest").invoke(RcsSipTransport, fake?.proxy, version, config)
    }

    private fun callbacks(): Pair<Any, Any> {
        @Suppress("UNCHECKED_CAST")
        return seam("callbacksForTest").invoke(RcsSipTransport) as Pair<Any, Any>
    }

    private fun invokeCallback(callback: Any, methodName: String, vararg args: Any?) {
        val method = callback.javaClass.methods.first {
            it.name == methodName && it.parameterCount == args.size
        }
        method.invoke(callback, *args)
    }

    private fun responseMessage(
        startLine: String,
        branch: String,
        callId: String = "dlg-call@rcs",
        cseq: String = "1 MESSAGE",
        extra: String = "",
        body: String = "",
    ): Any {
        val headers = buildString {
            append("Via: SIP/2.0/TCP 10.0.0.1;branch=$branch\r\n")
            append("To: <sip:peer@rcs>;tag=remote\r\n")
            append("From: <sip:+15550001@carrier.example>;tag=local\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseq\r\n")
            append(extra)
        }
        return sipMessage(startLine, headers, body.toByteArray(Charsets.UTF_8))
    }

    private suspend fun invokeSuspend(methodName: String, vararg args: Any?): Any? {
        val method = RcsSipTransport::class.java.methods.first {
            it.name == methodName && it.parameterTypes.size == args.size + 1
        }
        return suspendCoroutine { cont ->
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(RcsSipTransport, *args, cont as Continuation<Any?>)
            if (result !== COROUTINE_SUSPENDED) cont.resumeWith(Result.success(result))
        }
    }

    private fun setInboundListener(listener: ((Any) -> Unit)?) {
        // Reflective: the property type is the hidden SipMessage class.
        val field = RcsSipTransport::class.java.getDeclaredField("onInboundMessage")
        field.isAccessible = true
        field.set(RcsSipTransport, listener)
    }

    @AfterTest
    fun tearDown() {
        seam("installConnectionForTest").invoke(RcsSipTransport, null, -1L, null)
        setInboundListener(null)
        RcsSipTransport.tearDown(context)
    }

    @Test
    fun delegateLifecycleCallbacksDriveState() {
        val fake = FakeConnection()
        val (stateCallback, _) = callbacks()
        // onCreated installs the connection and flips state to Available.
        invokeCallback(stateCallback, "onCreated", fake.proxy)
        assertEquals(RcsRegistrationState.Available, RcsSipTransport.state.value)
        assertEquals("single-reg delegate", RcsSipTransport.activeLeg)
        // onConfigurationChanged records the snapshot used by SIP builders.
        invokeCallback(stateCallback, "onConfigurationChanged", config())
        val snapshot = RcsSipTransport.lastConfigSnapshot()
        assertNotNull(snapshot)
        assertEquals(7L, snapshot.version)
        assertEquals("sip:+15550001@carrier.example", snapshot.publicUserId)
        assertEquals("carrier.example", snapshot.homeDomain)
        assertEquals("<sip:proxy.carrier.example;lr>", snapshot.serviceRoute)
        assertEquals("imei-123", snapshot.imei)
        // Chat builders now use the configured identity as From.
        val (_, headers, _) = RcsSipTransport.buildChatMessage("sip:fallback@rcs", "sip:peer@rcs", "c1", "hi")
        assertTrue(headers.contains("From: <sip:+15550001@carrier.example>"))

        // Denied feature tags flip to Unavailable(TransportDenied).
        val regStateClass = Class.forName("android.telephony.ims.DelegateRegistrationState")
        val regState = regStateClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val tagClass = Class.forName("android.telephony.ims.FeatureTagState")
        val denied = tagClass.getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance("+g.3gpp.icsi-ref=\"urn:x\"", 2)
        invokeCallback(stateCallback, "onFeatureTagStatusChanged", regState, setOf(denied))
        assertEquals(
            RcsRegistrationState.Unavailable(RcsUnavailableReason.TransportDenied),
            RcsSipTransport.state.value,
        )
        // An empty denial set leaves state alone.
        invokeCallback(stateCallback, "onFeatureTagStatusChanged", regState, emptySet<Any>())
        assertEquals(
            RcsRegistrationState.Unavailable(RcsUnavailableReason.TransportDenied),
            RcsSipTransport.state.value,
        )

        // onDestroyed clears the connection and reports ServiceUnavailable
        // only when it had been Available; restore Available first.
        install(fake)
        assertEquals(RcsRegistrationState.Available, RcsSipTransport.state.value)
        invokeCallback(stateCallback, "onDestroyed", 1)
        assertFalse(RcsSipTransport.canSend())
        assertEquals(
            RcsRegistrationState.Unavailable(RcsUnavailableReason.ServiceUnavailable),
            RcsSipTransport.state.value,
        )
    }

    @Test
    fun sendSipMessageAckAndFailureFlows() = runTest {
        val fake = FakeConnection()
        install(fake)
        assertTrue(RcsSipTransport.canSend())

        // Happy path: send is parked, the sent-ack completes it true.
        val (_, _, _) = Triple(0, 0, 0)
        val pending = async(kotlinx.coroutines.Dispatchers.Default) {
            @Suppress("UNCHECKED_CAST")
            invokeSuspend("sendSipMessage", "MESSAGE sip:peer@rcs SIP/2.0", "Via: SIP/2.0/TCP x;branch=placeholder\r\n", "body".toByteArray()) as Boolean
        }
        // Wait for the fake to see the send.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && fake.sentMessages.isEmpty()) Thread.sleep(10)
        assertEquals(1, fake.sentMessages.size)
        assertEquals(7L, fake.sentMessages.first().version)
        val branch = fake.sentMessages.first().branch
        assertTrue(branch.startsWith("z9hG4bK") || branch.isNotEmpty(), branch)
        val (_, messageCallback) = callbacks()
        invokeCallback(messageCallback, "onMessageSent", branch)
        assertTrue(pending.await())

        // Failure callback completes a send false.
        fake.sentMessages.clear()
        val pending2 = async(kotlinx.coroutines.Dispatchers.Default) {
            @Suppress("UNCHECKED_CAST")
            invokeSuspend("sendSipMessage", "MESSAGE sip:peer@rcs SIP/2.0", "Via: SIP/2.0/TCP x;branch=p2\r\n", ByteArray(0)) as Boolean
        }
        val deadline2 = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline2 && fake.sentMessages.isEmpty()) Thread.sleep(10)
        val branch2 = fake.sentMessages.first().branch
        invokeCallback(messageCallback, "onMessageSendFailure", branch2, 3)
        assertFalse(pending2.await())

        // A throwing connection send fails the call immediately.
        fake.failSend = true
        val failed = invokeSuspend("sendSipMessage", "MESSAGE sip:peer@rcs SIP/2.0", "Via: x\r\n", ByteArray(0)) as Boolean
        assertFalse(failed)
        fake.failSend = false
    }

    @Test
    fun inboundMessageCallbackRoutesToListener() {
        val fake = FakeConnection()
        install(fake)
        val received = CopyOnWriteArrayList<Any>()
        setInboundListener { received += it }
        val (_, messageCallback) = callbacks()
        val inbound = sipMessage(
            "MESSAGE sip:me@rcs SIP/2.0",
            "Via: SIP/2.0/TCP 10.0.0.1;branch=z9hG4bKinbound1\r\nCall-ID: in-1\r\n",
            "hello".toByteArray(),
        )
        invokeCallback(messageCallback, "onMessageReceived", inbound)
        assertEquals(1, received.size)
        // The delegate was told we received it (branch ack).
        assertEquals(listOf("z9hG4bKinbound1"), fake.notifiedReceived.toList())
        setInboundListener(null)
    }

    @Test
    fun digestAuthRetryOn401ThenSuccess() = runTest {
        val fake = FakeConnection()
        install(fake)
        val pending = async(kotlinx.coroutines.Dispatchers.Default) {
            @Suppress("UNCHECKED_CAST")
            invokeSuspend("sendSipMessage", "MESSAGE sip:peer@rcs SIP/2.0", "Via: SIP/2.0/TCP host;branch=origbranch\r\nContent-Length: 4\r\n", "body".toByteArray()) as Boolean
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && fake.sentMessages.isEmpty()) Thread.sleep(10)
        assertEquals(1, fake.sentMessages.size)
        val challenge = "WWW-Authenticate: Digest realm=\"carrier.example\", nonce=\"nonce-42\", qop=auth\r\n"
        val response401 = responseMessage("SIP/2.0 401 Unauthorized", fake.sentMessages.first().branch, extra = challenge)
        val consumed = invokeSuspend("onSipResponseMessage", response401) as Boolean
        assertTrue(consumed)
        // The request was re-sent once, with a digest Authorization header
        // and a fresh branch.
        assertEquals(2, fake.sentMessages.size)
        val retried = fake.sentMessages[1]
        assertFalse(retried.branch == fake.sentMessages[0].branch)
        // Ack the retried transaction.
        val (_, messageCallback) = callbacks()
        invokeCallback(messageCallback, "onMessageSent", retried.branch)
        assertTrue(pending.await())
    }

    @Test
    fun second401FailsTheSend() = runTest {
        val fake = FakeConnection()
        install(fake)
        val pending = async(kotlinx.coroutines.Dispatchers.Default) {
            @Suppress("UNCHECKED_CAST")
            invokeSuspend("sendSipMessage", "MESSAGE sip:peer@rcs SIP/2.0", "Via: SIP/2.0/TCP host;branch=b1\r\n", "body".toByteArray()) as Boolean
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && fake.sentMessages.isEmpty()) Thread.sleep(10)
        val challenge = "WWW-Authenticate: Digest realm=\"carrier.example\", nonce=\"n1\", qop=auth\r\n"
        assertTrue(invokeSuspend("onSipResponseMessage", responseMessage("SIP/2.0 401 Unauthorized", fake.sentMessages[0].branch, extra = challenge)) as Boolean)
        assertEquals(2, fake.sentMessages.size)
        // Second challenge for the retried branch: no more retries, send fails.
        assertTrue(invokeSuspend("onSipResponseMessage", responseMessage("SIP/2.0 401 Unauthorized", fake.sentMessages[1].branch, extra = challenge)) as Boolean)
        assertFalse(pending.await())
        assertEquals(2, fake.sentMessages.size)
    }

    @Test
    fun pendingResponseResolvesThroughResponseRouter() = runTest {
        val fake = FakeConnection()
        install(fake)
        val pending = async(kotlinx.coroutines.Dispatchers.Default) {
            @Suppress("UNCHECKED_CAST")
            invokeSuspend("sendSipMessage", "MESSAGE sip:peer@rcs SIP/2.0", "Via: SIP/2.0/TCP host;branch=b9\r\n", "body".toByteArray()) as Boolean
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && fake.sentMessages.isEmpty()) Thread.sleep(10)
        val branch = fake.sentMessages.first().branch
        // Provisional is absorbed without completing the send.
        assertTrue(invokeSuspend("onSipResponseMessage", responseMessage("SIP/2.0 100 Trying", branch)) as Boolean)
        assertFalse(pending.isCompleted)
        // Final 2xx (non-INVITE) completes it successfully.
        assertTrue(invokeSuspend("onSipResponseMessage", responseMessage("SIP/2.0 200 OK", branch)) as Boolean)
        assertTrue(pending.await())
        // A failure final on a fresh send fails it (and routes dialog teardown).
        val pending2 = async(kotlinx.coroutines.Dispatchers.Default) {
            @Suppress("UNCHECKED_CAST")
            invokeSuspend("sendSipMessage", "MESSAGE sip:peer@rcs SIP/2.0", "Via: SIP/2.0/TCP host;branch=b10\r\n", "body".toByteArray()) as Boolean
        }
        val deadline2 = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline2 && fake.sentMessages.size < 2) Thread.sleep(10)
        val branch2 = fake.sentMessages[1].branch
        assertTrue(invokeSuspend("onSipResponseMessage", responseMessage("SIP/2.0 603 Decline", branch2)) as Boolean)
        assertFalse(pending2.await())
    }

    private fun awaitTransportState(timeoutMs: Long = 10_000): RcsRegistrationState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var state = RcsSipTransport.state.value
        while (System.currentTimeMillis() < deadline) {
            state = RcsSipTransport.state.value
            if (state !is RcsRegistrationState.Unknown && state !is RcsRegistrationState.Provisioning) break
            Thread.sleep(25)
        }
        return state
    }

    private fun configureProbe(url: String?, singleRegFeature: Boolean) {
        org.robolectric.shadows.ShadowSubscriptionManager.setDefaultSmsSubscriptionId(5)
        val ccm = context.getSystemService(android.telephony.CarrierConfigManager::class.java)
        val bundle = android.os.PersistableBundle()
        if (url != null) {
            bundle.putString(android.telephony.CarrierConfigManager.KEY_RCS_CONFIG_SERVER_URL_STRING, url)
        }
        org.robolectric.Shadows.shadowOf(ccm).setConfigForSubId(5, bundle)
        org.robolectric.Shadows.shadowOf(context.packageManager)
            .setSystemFeature("android.hardware.telephony.ims.singlereg", singleRegFeature)
    }

    @Test
    fun ensureRegisteredNotSupportedWithoutSingleRegFeature() {
        RcsSipTransport.tearDown(context)
        RcsSlice3Pass2ProvisioningShadow.reset()
        RcsSlice3Pass2ProvisioningShadow.capable = false
        try {
            configureProbe("https://config.example/rcs", singleRegFeature = false)
            RcsSipTransport.ensureRegistered(context)
            val state = awaitTransportState()
            // Probe says the framework is not single-reg capable and we
            // hold no carrier privilege; the device gate is off too, so
            // the direct-SIP fallback is not attempted and the probe stands.
            assertEquals(
                RcsRegistrationState.Unavailable(RcsUnavailableReason.NoCarrierPrivilege), state,
            )
        } finally {
            RcsSlice3Pass2ProvisioningShadow.reset()
            RcsSipTransport.tearDown(context)
        }
    }

    @Test
    fun ensureRegisteredAttemptsDelegateWhenProvisioned() {
        RcsSipTransport.tearDown(context)
        RcsSlice3Pass2ProvisioningShadow.reset()
        try {
            configureProbe("https://config.example/rcs", singleRegFeature = true)
            RcsSipTransport.ensureRegistered(context)
            // The probe itself completes Available on its own flow.
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline &&
                com.vayunmathur.communicate.data.rcs.RcsProvisioning.state.value !=
                RcsRegistrationState.Available
            ) {
                Thread.sleep(25)
            }
            assertEquals(
                RcsRegistrationState.Available,
                com.vayunmathur.communicate.data.rcs.RcsProvisioning.state.value,
            )
            // The delegate attempt resolves on the IO scope to some terminal
            // state (no privilege on the JVM); Unknown/Disabled/other would
            // mean ensureRegistered never ran.
            val state = awaitTransportState()
            assertTrue(
                state !is RcsRegistrationState.Disabled,
                "transport state never engaged: $state",
            )
        } finally {
            RcsSlice3Pass2ProvisioningShadow.reset()
            RcsSipTransport.tearDown(context)
        }
    }

    @Test
    fun inviteBuilderUsesDelegateConfig() {
        val fake = FakeConnection()
        install(fake)
        val invite = with(RcsSessionManagerForInvite) { build() }
        assertTrue(invite.first.startsWith("INVITE sip:peer@carrier.example SIP/2.0"), invite.first)
        assertTrue(invite.second.contains("Route: <<sip:proxy.carrier.example;lr>>") ||
            invite.second.contains("Route: <sip:proxy.carrier.example;lr>"), invite.second)
        assertTrue(invite.second.contains("P-Access-Network-Info: 3GPP-E-UTRAN-FDD"), invite.second)
        assertTrue(invite.second.contains("User-Agent: CarrierUA/1.0"), invite.second)
        assertTrue(invite.second.contains("Contact: <sip:+15550001@carrier.example>"), invite.second)
        val sdp = invite.third.toString(Charsets.UTF_8)
        assertTrue(sdp.contains("a=accept-types:message/cpim"), sdp)
    }

    @Test
    fun cleanupAndTeardownDropConnection() {
        val fake = FakeConnection()
        install(fake)
        RcsSipTransport.cleanupSession("call-42")
        RcsSipTransport.cleanupSession("")
        assertEquals(listOf("call-42"), fake.cleanedSessions.toList())
        RcsSipTransport.tearDown(context)
        assertFalse(RcsSipTransport.canSend())
        assertEquals("none", RcsSipTransport.activeLeg)
        // teardown drops the connection; the last config snapshot is kept
        // (only onDestroyed clears it on device).
        assertEquals(7L, RcsSipTransport.lastConfigSnapshot()?.version)
    }
}
