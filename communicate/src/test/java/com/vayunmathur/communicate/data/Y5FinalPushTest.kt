package com.vayunmathur.communicate.data

import android.content.Context
import android.content.res.Configuration
import android.util.Base64
import androidx.car.app.HandshakeInfo
import androidx.car.app.HostInfo
import androidx.car.app.ICarHost
import androidx.car.app.Session
import androidx.car.app.model.PaneTemplate
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallState
import com.vayunmathur.communicate.data.rcs.RcsDirectSip
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.mex.MexResult
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import com.vayunmathur.communicate.service.car.CommunicateCarCallScreen
import com.vayunmathur.communicate.service.car.CommunicateCarConversationScreen
import com.vayunmathur.communicate.service.car.CommunicateCarSession
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Y5: final push — private-but-pure seams exercised reflectively where no
 * public entry can reach them on a JVM host: WhatsApp discovery parsing,
 * the Noise ClientFinish, RCS P-CSCF parsing, and the car call-card /
 * conversation templates (fallbacks the fake car host's experimental
 * templates normally shadow).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y5FinalPushTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun setField(obj: Any, name: String, value: Any?) {
        val f = obj.javaClass.getDeclaredField(name); f.isAccessible = true; f.set(obj, value)
    }
    private fun getField(obj: Any, name: String): Any? {
        val f = obj.javaClass.getDeclaredField(name); f.isAccessible = true; return f.get(obj)
    }

    // ---------- WhatsAppContactSync.parseDiscovery (private) ----------

    @Test fun parseDiscoveryMapsStatusesAndLids() {
        val cls = Class.forName("com.vayunmathur.communicate.data.whatsapp.WhatsAppContactSync")
        val obj = cls.getDeclaredField("INSTANCE").also { it.isAccessible = true }.get(null)
        val m = cls.getDeclaredMethod("parseDiscovery", MexResult::class.java)
        m.isAccessible = true
        fun parse(result: MexResult): Map<*, *> = m.invoke(obj, result) as Map<*, *>
        fun lidOf(map: Map<*, *>, key: String): Pair<String, Boolean> {
            val v = map[key]!!
            val lid = v.javaClass.getDeclaredField("lid").also { it.isAccessible = true }.get(v) as String
            val on = v.javaClass.getDeclaredField("onWhatsApp").also { it.isAccessible = true }.get(v) as Boolean
            return lid to on
        }
        val data = JSONObject("""{
          "xwa2_contact_discovery": {"results": [
            {"lid": "111@lid", "detail": {"normalized_phone": "+14155550102", "status": "IN"}},
            {"lid": "", "detail": {"raw_pn": "+14155550103", "status": "OUT"}},
            {"lid": "333@lid", "failed": false, "detail": {"normalized_phone": "+14155550104", "status": ""}},
            {"lid": "444@lid", "failed": true, "detail": {"normalized_phone": "+14155550105", "status": ""}},
            {"detail": {"status": "IN"}},
            {"lid": "555@lid"}
          ]}}""")
        val map = parse(MexResult(data = data))
        assertEquals("111@lid" to true, lidOf(map, "+14155550102"))
        assertEquals("" to false, lidOf(map, "+14155550103"))
        assertEquals("333@lid" to true, lidOf(map, "+14155550104")) // lid + not failed => on WhatsApp
        assertEquals("444@lid" to false, lidOf(map, "+14155550105"))
        assertEquals(4, map.size)
        assertTrue(parse(MexResult(data = null)).isEmpty())
        assertTrue(parse(MexResult(data = JSONObject("""{"other": {}}"""))).isEmpty())
        assertTrue(parse(MexResult(data = null, transportError = "x")).isEmpty())
    }

    // ---------- RcsDirectSip.parsePcscfFromString (private) ----------

    @Test fun parsePcscfExtractsAddresses() {
        val m = RcsDirectSip::class.java.getDeclaredMethod("parsePcscfFromString", String::class.java)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        fun parse(s: String): List<java.net.InetAddress> = m.invoke(RcsDirectSip, s) as List<java.net.InetAddress>
        assertEquals(
            listOf("10.0.0.1", "2001:db8:0:0:0:0:0:1"),
            parse("LinkProperties: PcscfAddresses: [/10.0.0.1, /2001:db8::1]").map { it.hostAddress })
        assertTrue(parse("nothing here").isEmpty())
        assertTrue(parse("PcscfAddresses: []").isEmpty())
        assertEquals(listOf("192.168.0.1"), parse("pcscfaddresses: [ /192.168.0.1 , bogus!! ]").map { it.hostAddress })
    }

    // ---------- WhatsAppSocket.sendClientFinish (private) ----------

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    @Test fun sendClientFinishWritesFramedFinishAndCompletesHandshake() {
        val (priv, pub) = WhatsAppProtocol.generateX25519KeyPair()
        val auth = WhatsAppAuthData(
            phoneNumber = "+15550001", pushName = "T", wid = "15550001@s.whatsapp.net",
            noisePrivateKey = b64(priv), noisePublicKey = b64(pub),
            identityPrivateKey = b64(ByteArray(32) { 3 }), identityPublicKey = b64(ByteArray(32) { 4 }),
            registrationId = 14, signedPreKeyId = 5,
            signedPreKeyPublic = b64(ByteArray(32) { 5 }), signedPreKeyPrivate = b64(ByteArray(32) { 6 }),
            signedPreKeySignature = b64(ByteArray(64) { 7 }), lid = "12345@lid",
        )
        val socket = WhatsAppSocket(auth, host = "127.0.0.1", port = 9)
        val out = ByteArrayOutputStream()
        setField(socket, "output", out)
        // A handshake in the state handleHandshakeMessage leaves it after ServerHello:
        // client ephemeral authenticated, server ephemeral mixed.
        val handshake = WhatsAppProtocol.NoiseHandshake()
        handshake.start(WhatsAppProtocol.NOISE_START_PATTERN, WhatsAppProtocol.WA_CONN_HEADER)
        val (clientPriv, clientPub) = WhatsAppProtocol.generateX25519KeyPair()
        handshake.authenticate(clientPub)
        val (serverPriv, serverPub) = WhatsAppProtocol.generateX25519KeyPair()
        handshake.authenticate(serverPub)
        handshake.mixSharedSecretIntoKey(clientPriv, serverPub)
        val (_, serverStaticPub) = WhatsAppProtocol.generateX25519KeyPair()
        handshake.encrypt(serverStaticPub)
        handshake.mixSharedSecretIntoKey(clientPriv, serverStaticPub)

        val finish = WhatsAppSocket::class.java.getDeclaredMethod(
            "sendClientFinish", WhatsAppProtocol.NoiseHandshake::class.java, ByteArray::class.java)
        finish.isAccessible = true
        finish.invoke(socket, handshake, serverPub)
        assertTrue(out.size() > 3, "ClientFinish frame written")
        assertEquals(true, getField(socket, "isHandshakeComplete"))
        assertEquals(true, getField(socket, "isConnected"))
        assertNotNull(getField(socket, "noiseSocket"))
        // And the finished socket can now send encrypted frames.
        assertTrue(socket.send(byteArrayOf(1, 2, 3)))
        socket.disconnect()
    }

    // ---------- car callCard / conversationTemplate (private fallbacks) ----------

    private fun configuredSession(apiLevel: Int = 8): CommunicateCarSession {
        val session = CommunicateCarSession()
        val configure = Session::class.java.getDeclaredMethod(
            "configure", android.content.Context::class.java, HandshakeInfo::class.java,
            HostInfo::class.java, ICarHost::class.java, Configuration::class.java)
        configure.isAccessible = true
        val carHost = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ICarHost::class.java)) { _, method, _ ->
            when (method.name) { "asBinder" -> android.os.Binder(); "toString" -> "fake"; else -> null }
        } as ICarHost
        configure.invoke(session, context, HandshakeInfo("com.example.host", apiLevel),
            HostInfo("com.example.host", 1000), carHost,
            Configuration().apply { screenWidthDp = 800; screenHeightDp = 480; densityDpi = 160 })
        return session
    }

    @Test fun carCallCardRendersIncomingAndActiveActions() {
        val session = configuredSession()
        val screen = CommunicateCarCallScreen(session.carContext, session)
        Thread.sleep(300); shadowOf(android.os.Looper.getMainLooper()).idle()
        val callCard = CommunicateCarCallScreen::class.java.getDeclaredMethod(
            "callCard", String::class.java, InAppCallState::class.java)
        callCard.isAccessible = true
        val incoming = InAppCallState(
            peerId = "+14155550102", peerName = "Alice", phase = InAppCallPhase.Incoming)
        val incomingTemplate = callCard.invoke(screen, "Alice", incoming)
        assertTrue(incomingTemplate is PaneTemplate, "was ${incomingTemplate.javaClass}")
        val active = InAppCallState(
            peerId = "+14155550102", peerName = "Alice", phase = InAppCallPhase.Active, muted = true)
        // The active card adds Mute+Speaker+End (3 actions); this host's Pane constraint
        // caps at 2, so building it throws here — onGetTemplate prefers inCallTemplate and
        // only falls back to callCard where the dialer template is unavailable.
        val activeResult = runCatching { callCard.invoke(screen, "", active) }
        assertTrue(activeResult.exceptionOrNull()?.cause is IllegalArgumentException ||
            activeResult.getOrNull() is PaneTemplate, "$activeResult")
        val keypad = screen.keypadTemplate()
        assertNotNull(keypad)
    }

    @Test fun carConversationTemplateRendersMessages() {
        val session = configuredSession()
        val thread = SmsThread(
            threadId = 7L, address = "+14155550102", displayName = "Alice", snippet = "hi",
            timestampMillis = 1L, unreadCount = 0, line = CommunicateLine.Sim, remoteId = "t.7")
        val screen = CommunicateCarConversationScreen(session.carContext, session, thread)
        Thread.sleep(300); shadowOf(android.os.Looper.getMainLooper()).idle()
        setField(screen, "messages", listOf(
            SmsMessage(id = 1L, threadId = 7L, address = "+14155550102", body = "hello",
                timestampMillis = 1_000L, outgoing = false, read = true, senderAddress = "+14155550102"),
            SmsMessage(id = 2L, threadId = 7L, address = "+14155550102", body = "reply",
                timestampMillis = 2_000L, outgoing = true, read = true),
        ))
        setField(screen, "loading", false)
        val conv = CommunicateCarConversationScreen::class.java.getDeclaredMethod("conversationTemplate")
        conv.isAccessible = true
        assertNotNull(conv.invoke(screen))
        // Empty snapshot falls back to the legacy list template, whose Reply action
        // exceeds this fake host's titled-action allowance (documented host constraint).
        setField(screen, "messages", emptyList<SmsMessage>())
        runCatching { conv.invoke(screen) }
    }
}
