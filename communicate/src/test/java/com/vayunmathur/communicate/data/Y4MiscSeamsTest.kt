package com.vayunmathur.communicate.data

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.util.Base64
import androidx.car.app.HandshakeInfo
import androidx.car.app.HostInfo
import androidx.car.app.ICarHost
import androidx.car.app.Session
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.testing.TestCarContext
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.googlevoice.GvSipRegisterInfo
import com.vayunmathur.communicate.data.googlevoice.call.SipClient
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.e2e.commitLockFor
import com.vayunmathur.communicate.data.rcs.e2e.parseMembers
import com.vayunmathur.communicate.data.rcs.e2e.pruneMembers
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalContact
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalProtocol
import com.vayunmathur.communicate.data.signal.conversationIdFor
import com.vayunmathur.communicate.data.signal.displayNameFor
import com.vayunmathur.communicate.data.signal.pendingIdentityChange
import com.vayunmathur.communicate.data.signal.profileKeyFrom
import com.vayunmathur.communicate.data.signal.reportIdentityChange
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import com.vayunmathur.communicate.data.whatsapp.transport.PrimaryClientPayload
import com.vayunmathur.communicate.service.car.CommunicateCarSession
import com.vayunmathur.communicate.service.car.CommunicateCarThreadListScreen
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * Y4: leftover seams — car thread-row/monogram rendering with a reflectively
 * seeded snapshot, PrimaryClientPayload building, SipClient reliable
 * provisional (PRACK) handling, Signal identity contact resolution, RCS MLS
 * member-map helpers, and WhatsApp incoming-media spec mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y4MiscSeamsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var sigDb: SignalDatabase

    @BeforeTest fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        sigDb = Room.inMemoryDatabaseBuilder(app, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        SignalClient.db = sigDb
        SignalClient.authData = SignalAuthData(
            phoneNumber = "+14155552671", aci = "3f0e5a2c-1111-2222-3333-444455556666", password = "pw")
        SignalClient.e2e = null
        SignalClient.pendingIdentityChanges.clear()
    }

    @AfterTest fun tearDown() {
        SignalClient.db = null; SignalClient.authData = null
        SignalClient.pendingIdentityChanges.clear()
        sigDb.close()
    }

    private fun setField(obj: Any, name: String, value: Any?) {
        val f = obj.javaClass.getDeclaredField(name); f.isAccessible = true; f.set(obj, value)
    }

    // ---------- car thread rows / monogram ----------

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

    @Test fun carThreadRowsRenderNamesSnippetsUnreadAndMonograms() {
        val session = configuredSession()
        val screen = CommunicateCarThreadListScreen(session.carContext, session)
        Thread.sleep(400); shadowOf(android.os.Looper.getMainLooper()).idle()
        val threads = listOf(
            SmsThread(threadId = 1L, address = "+14155550102", displayName = "Alice Lee", snippet = "hi",
                timestampMillis = 3L, unreadCount = 2, line = CommunicateLine.Sim),
            SmsThread(threadId = 2L, address = "+442079460958", displayName = null, snippet = "",
                timestampMillis = 2L, unreadCount = 0, line = CommunicateLine.WhatsApp,
                remoteId = "442079460958@s.whatsapp.net", avatarUrl = "content://photos/1"),
            SmsThread(threadId = 3L, address = "grp", displayName = "  ", snippet = "group msg",
                timestampMillis = 1L, unreadCount = 1, line = CommunicateLine.Signal, isGroup = true),
        )
        setField(screen, "threads", threads)
        setField(screen, "loading", false)
        val sectioned = screen.onGetTemplate()
        assertTrue(sectioned is SectionedItemTemplate, "was ${sectioned.javaClass}")
        // Legacy (pre-API-8) flat list path with the same rows.
        val legacySession = configuredSession(apiLevel = 7)
        val legacy = CommunicateCarThreadListScreen(legacySession.carContext, legacySession)
        Thread.sleep(400); shadowOf(android.os.Looper.getMainLooper()).idle()
        setField(legacy, "threads", threads)
        setField(legacy, "loading", false)
        assertTrue(legacy.onGetTemplate() is ListTemplate)
    }

    // ---------- PrimaryClientPayload ----------

    private fun waAuth(wid: String, phone: String) = WhatsAppAuthData(
        phoneNumber = phone, pushName = "T", wid = wid,
        noisePrivateKey = "", noisePublicKey = "", identityPrivateKey = "", identityPublicKey = "",
        registrationId = 1, signedPreKeyId = 1, signedPreKeyPublic = "", signedPreKeyPrivate = "",
        signedPreKeySignature = "",
    )

    @Test fun primaryPayloadParsesUsernameFromWidVariants() {
        fun usernameOf(wid: String, phone: String): Long =
            com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppPayloadProto.ClientPayload
                .parseFrom(PrimaryClientPayload.build(waAuth(wid, phone))).username
        assertEquals(15550001L, usernameOf("15550001@s.whatsapp.net", "+15550001"))
        assertEquals(15550001L, usernameOf("15550001:3@s.whatsapp.net", "+15550001"))
        assertEquals(442079460958L, usernameOf("not-a-number@x", "+44 20 7946 0958"))
        assertEquals(0L, usernameOf("", "no digits"))
        val payload = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppPayloadProto.ClientPayload
            .parseFrom(PrimaryClientPayload.build(waAuth("15550001@s.whatsapp.net", "+15550001")))
        assertEquals(0, payload.device)
        assertTrue(!payload.passive && !payload.pull)
        assertEquals(
            com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.UserAgent.Platform.ANDROID,
            payload.userAgent.platform)
    }

    // ---------- SipClient PRACK / early media ----------

    @Test fun sipProvisionalWithRseqPracksAfterInvite() {
        val events = mutableListOf<String>()
        val listener = object : SipClient.Listener {
            override fun onRegistered() { events += "registered" }
            override fun onProvisional(code: Int) { events += "prov:$code" }
            override fun onAnswered(remoteSdp: String) { events += "answered" }
            override fun onEarlyMedia(remoteSdp: String) { events += "early" }
            override fun onEnded() { events += "ended" }
            override fun onFailed(reason: String) { events += "failed:$reason" }
            override fun onIncomingInvite(remoteSdp: String, from: String) { events += "invite" }
        }
        val client = SipClient(
            GvSipRegisterInfo(phoneNumber = "+15550001", credentials = listOf("user=1", "secret")),
            "+15550001", CoroutineScope(Dispatchers.Unconfined), listener)
        runTest {
            client.invite("+15559999", "v=0\r\nm=audio 9 RTP/AVP 0\r\n")
        }
        val handle = SipClient::class.java.getDeclaredMethod("handleIncoming", String::class.java)
        handle.isAccessible = true
        val sdp = "v=0\r\nm=audio 9 RTP/AVP 0\r\n"
        handle.invoke(client,
            "SIP/2.0 183 Session Progress\r\nCSeq: 2 INVITE\r\nRSeq: 77\r\n" +
                "Content-Type: application/sdp\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp")
        assertTrue("prov:183" in events, "$events")
        assertTrue("early" in events, "$events")
        Thread.sleep(300) // PRACK is launched on the client scope (no socket -> send is a no-op)
    }

    // ---------- Signal identity/contact resolution ----------

    @Test fun signalDisplayNameAndConversationResolveThroughContacts() = runTest {
        sigDb.contactDao().upsert(
            SignalContact(aci = "aci-9", phoneE164 = "+14155550109", displayName = "Carol", onSignal = true, pni = "pni-9"))
        with(SignalClient) {
            assertEquals("Carol", displayNameFor("aci-9"))
            assertEquals("Carol", displayNameFor("pni-9"))
            assertEquals("+14155550109", conversationIdFor("aci-9"))
            assertEquals("unknown-id", displayNameFor("unknown-id"))
            assertEquals("unknown-id", conversationIdFor("unknown-id"))
        }
        sigDb.contactDao().upsert(
            SignalContact(aci = "aci-10", phoneE164 = "+14155550110", displayName = "", onSignal = true))
        assertEquals("+14155550110", SignalClient.displayNameFor("aci-10"))
    }

    @Test fun profileKeyFromDataEditAndOtherContent() {
        val key = ByteArray(32) { it.toByte() }
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setBody("hi").setProfileKey(ByteString.copyFrom(key)).build()
        val raw = SignalServiceProtos.Content.newBuilder().setDataMessage(dm).build()
        val data = SignalProtocol.ParsedContent.Data(dm, raw)
        assertTrue(SignalClient.profileKeyFrom(data)!!.contentEquals(key))
        val edit = SignalProtocol.ParsedContent.Edit(
            SignalServiceProtos.EditMessage.newBuilder().setDataMessage(dm).build())
        assertTrue(SignalClient.profileKeyFrom(edit)!!.contentEquals(key))
        val editNoKey = SignalProtocol.ParsedContent.Edit(
            SignalServiceProtos.EditMessage.newBuilder().build())
        assertNull(SignalClient.profileKeyFrom(editNoKey))
        val noKey = SignalProtocol.ParsedContent.Data(
            SignalServiceProtos.DataMessage.newBuilder().setBody("x").build(), raw)
        assertNull(SignalClient.profileKeyFrom(noKey))
        assertNull(SignalClient.profileKeyFrom(SignalProtocol.ParsedContent.Null(
            SignalServiceProtos.NullMessage.newBuilder().build())))
    }

    @Test fun reportIdentityChangeRecordsPendingKey() {
        val key = ByteArray(33) { (it + 1).toByte() }
        SignalClient.reportIdentityChange("aci-1", key)
        assertTrue(SignalClient.pendingIdentityChange("aci-1")!!.contentEquals(key))
        assertNull(SignalClient.pendingIdentityChange("aci-2"))
    }

    // ---------- RCS MLS member helpers ----------

    @Test fun rcsMemberMapParsePruneAndLocks() {
        with(RcsE2E) {
            assertEquals(mapOf("+1" to 0, "+2" to 3), parseMembers("+1=0,+2=3"))
            assertEquals(emptyMap(), parseMembers(""))
            assertEquals(mapOf("+1" to 0), parseMembers("+1=0,bogus,+2=x,=5"))
            assertEquals("+1=0", pruneMembers("+1=0,+2=3", setOf(3)))
            assertEquals("", pruneMembers("+1=0", setOf(0)))
            assertTrue(commitLockFor("c1") === commitLockFor("c1"))
            assertTrue(commitLockFor("c1") !== commitLockFor("c2"))
        }
    }

    @Test fun mediaSpecForMapsEveryMediaKind() {
        val cls = Class.forName("com.vayunmathur.communicate.data.whatsapp.WhatsAppClientUtilsKt")
        val m = cls.getDeclaredMethod("mediaSpecFor",
            com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message::class.java)
        m.isAccessible = true
        fun spec(msg: com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message): Any? =
            m.invoke(null, msg)
        fun fieldOf(obj: Any, name: String): Any? {
            val f = obj.javaClass.getDeclaredField(name); f.isAccessible = true; return f.get(obj)
        }
        val image = WhatsAppE2EProto.Message.newBuilder().setImageMessage(
            WhatsAppE2EProto.ImageMessage.newBuilder().setUrl("https://x/i").setWidth(10).setHeight(20)).build()
        assertEquals("image", fieldOf(spec(image)!!, "type"))
        val sticker = WhatsAppE2EProto.Message.newBuilder().setStickerMessage(
            WhatsAppE2EProto.StickerMessage.newBuilder().setUrl("https://x/s")).build()
        assertEquals("webp", fieldOf(spec(sticker)!!, "ext"))
        val video = WhatsAppE2EProto.Message.newBuilder().setVideoMessage(
            WhatsAppE2EProto.VideoMessage.newBuilder().setUrl("https://x/v")).build()
        assertEquals("video", fieldOf(spec(video)!!, "type"))
        val audio = WhatsAppE2EProto.Message.newBuilder().setAudioMessage(
            WhatsAppE2EProto.AudioMessage.newBuilder().setUrl("https://x/a")).build()
        assertEquals("ogg", fieldOf(spec(audio)!!, "ext"))
        val doc = WhatsAppE2EProto.Message.newBuilder().setDocumentMessage(
            WhatsAppE2EProto.DocumentMessage.newBuilder().setUrl("https://x/d").setFileName("report.pdf")).build()
        assertEquals("pdf", fieldOf(spec(doc)!!, "ext"))
        assertEquals("report.pdf", fieldOf(spec(doc)!!, "fileName"))
        assertNull(spec(WhatsAppE2EProto.Message.newBuilder().setConversation("plain").build()))
    }
}
