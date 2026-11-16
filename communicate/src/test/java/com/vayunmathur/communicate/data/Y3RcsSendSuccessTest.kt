package com.vayunmathur.communicate.data

import android.content.Context
import android.net.Uri
import android.telephony.TelephonyManager
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsRcsManager
import android.telephony.ims.RcsUceAdapter
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFileTransferHttp
import com.vayunmathur.communicate.data.rcs.RcsMlsGroup
import com.vayunmathur.communicate.data.rcs.RcsOutbox
import com.vayunmathur.communicate.data.rcs.RcsSession
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.e2e.RustMlsCrypto
import com.vayunmathur.communicate.rcs.RcsSlice3Pass2ConnectivityShadow
import com.vayunmathur.communicate.rcs.RcsSlice3Pass2ProvisioningShadow
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSubscriptionManager

/** Reflective UCE capability: FOUND + OPTIONS + CPM session tag (see RcsCapabilityExchange.readCapability). */
class Y3FakeUceCap(
    private val uri: Uri,
    private val result: Int = 3,
    private val tags: Set<String> = setOf("+g.3gpp.icsi-ref=\"urn:urn-7:3gpp-service.ims.icsi.oma.cpm.session\""),
) {
    fun getContactUri(): Uri = uri
    fun getRequestResult(): Int = result
    private val capabilityMechanism: Int = 2
    fun getCapabilityMechanism(): Int = capabilityMechanism
    fun getFeatureTags(): Set<String> = tags
}

/** Valid-subscription ImsManager whose RCS manager is backed by [Y3RcsUceAdapterShadow]. */
@Implements(ImsManager::class)
class Y3ImsManagerShadow {
    @Implementation
    fun getImsRcsManager(subId: Int): ImsRcsManager {
        require(subId >= 0 || subId == -1) { "bad sub" }
        val ctor = ImsRcsManager::class.java.declaredConstructors.first { it.parameterCount == 0 }
        ctor.isAccessible = true
        return ctor.newInstance() as ImsRcsManager
    }
}

@Implements(ImsRcsManager::class)
class Y3ImsRcsManagerShadow {
    @Implementation
    fun getUceAdapter(): RcsUceAdapter {
        val ctor = RcsUceAdapter::class.java.declaredConstructors.first { it.parameterCount == 0 }
        ctor.isAccessible = true
        return ctor.newInstance() as RcsUceAdapter
    }
}

/**
 * UCE adapter reporting every requested contact capable (or only [capableNumbers]
 * when set). Hidden callback type, so loose signatures + reflective dispatch.
 */
@Implements(className = "android.telephony.ims.RcsUceAdapter")
class Y3RcsUceAdapterShadow {
    @Implementation
    fun requestCapabilities(
        uris: Collection<Uri>,
        executor: Executor,
        @org.robolectric.annotation.ClassName("android.telephony.ims.RcsUceAdapter\$CapabilitiesCallback") callback: Any,
    ) {
        val list = uris.map { capFor(it) }
        executor.execute { deliver(callback, list) }
    }

    @Implementation
    fun requestAvailability(
        uri: Uri,
        executor: Executor,
        @org.robolectric.annotation.ClassName("android.telephony.ims.RcsUceAdapter\$CapabilitiesCallback") callback: Any,
    ) {
        executor.execute { deliver(callback, listOf(capFor(uri))) }
    }

    private fun capFor(uri: Uri): Y3FakeUceCap {
        // Uri.fromParts encodes '+', so the wire uri is tel:%2B1...; the
        // production mapping keys off the decoded tel: number.
        val number = Uri.decode(uri.toString().substringAfter("tel:").substringBefore("@"))
        val plainUri = Uri.parse("tel:$number")
        val allowed = capableNumbers
        return if (allowed == null || number in allowed) {
            Y3FakeUceCap(plainUri)
        } else {
            Y3FakeUceCap(plainUri, result = 2, tags = emptySet())
        }
    }

    private fun deliver(callback: Any, caps: List<Y3FakeUceCap>) {
        val cls = callback.javaClass
        cls.getMethod("onCapabilitiesReceived", List::class.java).invoke(callback, caps)
        runCatching { cls.getMethod("onComplete").invoke(callback) }
    }

    companion object {
        @Volatile var capableNumbers: Set<String>? = null
        fun reset() { capableNumbers = null }
    }
}

/**
 * Y3: the RCS *success* sends in CommunicateRepositoryRcsKt, which W1/Y2 could
 * only pin fail-closed. A valid default-SMS subscription (ShadowSubscriptionManager,
 * as in RcsSlice3Pass2DelegateTest) plus a UCE adapter that reports contacts
 * capable makes isContactRcsCapable true, so pager / group / MSRP-session /
 * attachment / MLS sends run to the fake SIP sender and cache their rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [
        Y3ImsManagerShadow::class, Y3ImsRcsManagerShadow::class, Y3RcsUceAdapterShadow::class,
        RcsSlice3Pass2ProvisioningShadow::class, RcsSlice3Pass2ConnectivityShadow::class,
    ],
)
class Y3RcsSendSuccessTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: RcsDatabase
    private val sent = CopyOnWriteArrayList<Triple<String, String, ByteArray>>()
    private var sendResult = true

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        RcsDatabase.overrideForTest = db
        sent.clear(); sendResult = true
        RcsSipTransport.fakeSenderForTest = { line, headers, body ->
            sent += Triple(line, headers, body.copyOf()); sendResult
        }
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(5)
        Y3RcsUceAdapterShadow.reset()
        RcsSlice3Pass2ProvisioningShadow.reset()
        RcsSessionManager.sessionsMutable.value = emptyMap()
        RcsFileTransferHttp.contentServerUri = null
    }

    @AfterTest
    fun tearDown() {
        RcsSipTransport.fakeSenderForTest = null
        RcsDatabase.overrideForTest = null
        RcsSessionManager.sessionsMutable.value = emptyMap()
        RcsFileTransferHttp.contentServerUri = null
        Y3RcsUceAdapterShadow.reset()
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(-1)
        db.close()
    }

    private fun sentBodies(): List<String> = sent.map { it.third.toString(Charsets.UTF_8) }

    @Test
    fun `pager send succeeds for capable contact and caches one row`() = runTest {
        val result = CommunicateRepository.sendRcsMessage(context, "+14155550102", "hello pager y3")
        assertEquals(RcsSendResult.Sent, result)
        assertTrue(sentBodies().any { it.contains("hello pager y3") }, "$sent")
        val rows = db.cachedMessageDao().getForConversation("+14155550102")
        assertEquals(listOf("hello pager y3"), rows.map { it.body })
        assertTrue(rows.single().outgoing && rows.single().status == 1)
        assertNotNull(db.conversationDao().getConversation("+14155550102"))
    }

    @Test
    fun `pager large message chunks on the wire but caches the full body once`() = runTest {
        val big = "y3-large-" + "x".repeat(20_000)
        assertEquals(RcsSendResult.Sent, CommunicateRepository.sendRcsMessage(context, "+14155550103", big))
        val chunks = sentBodies().filter { it.contains("Part:") || it.contains("y3-large-") }
        assertTrue(chunks.size >= 3, "chunks=${chunks.size}")
        val rows = db.cachedMessageDao().getForConversation("+14155550103")
        assertEquals(listOf(big), rows.map { it.body })
    }

    @Test
    fun `pager transport failure falls back and parks outbox unless already from outbox`() = runTest {
        sendResult = false
        assertEquals(
            RcsSendResult.FallbackSms,
            CommunicateRepository.sendRcsMessage(context, "+14155550104", "park me y3"),
        )
        assertEquals(1, RcsOutbox.pendingCount(context))
        assertTrue(db.cachedMessageDao().getForConversation("+14155550104").isEmpty())
        // A retry that came FROM the outbox must not enqueue a second row.
        assertEquals(
            RcsSendResult.FallbackSms,
            CommunicateRepository.sendRcsMessage(context, "+14155550104", "park me y3", fromOutbox = true),
        )
        assertEquals(1, RcsOutbox.pendingCount(context))
    }

    @Test
    fun `group send fans out per member caches group and member rows and dedupes`() = runTest {
        val result = CommunicateRepository.sendRcsMessage(
            context, "+14155550102", "group hello y3",
            threadRemoteId = "rcs-group:y3g1",
            participants = listOf("+14155550102", "+14155550103", "+14155550102", " "),
        )
        assertEquals(RcsSendResult.Sent, result)
        assertEquals(listOf("group hello y3"), db.cachedMessageDao().getForConversation("rcs-group:y3g1").map { it.body })
        assertEquals(listOf("group hello y3"), db.cachedMessageDao().getForConversation("+14155550102").map { it.body })
        assertEquals(listOf("group hello y3"), db.cachedMessageDao().getForConversation("+14155550103").map { it.body })
        assertTrue(sentBodies().count { it.contains("group hello y3") } >= 2)
    }

    @Test
    fun `group send with one capable member still sends and caches the group row`() = runTest {
        Y3RcsUceAdapterShadow.capableNumbers = setOf("+14155550102")
        val result = CommunicateRepository.sendRcsMessage(
            context, "+14155550102", "partial group y3",
            threadRemoteId = "rcs-group:y3g2",
            participants = listOf("+14155550102", "+14155550199"),
        )
        assertEquals(RcsSendResult.Sent, result)
        assertEquals(listOf("partial group y3"), db.cachedMessageDao().getForConversation("rcs-group:y3g2").map { it.body })
        assertTrue(db.cachedMessageDao().getForConversation("+14155550199").isEmpty())
    }

    @Test
    fun `incapable contact still falls back before any SIP send`() = runTest {
        Y3RcsUceAdapterShadow.capableNumbers = emptySet()
        assertEquals(
            RcsSendResult.FallbackSms,
            CommunicateRepository.sendRcsMessage(context, "+14155550102", "never sent y3"),
        )
        assertTrue(sent.isEmpty())
        assertTrue(db.cachedMessageDao().getForConversation("+14155550102").isEmpty())
    }

    @Test
    fun `uri and group shaped recipients skip session start but still pager send`() = runTest {
        // '@' recipient: shouldStartSession is false, no session is ever created.
        assertEquals(
            RcsSendResult.Sent,
            CommunicateRepository.sendRcsMessage(context, "ignored", "at-sign y3", threadRemoteId = "peer@rcs"),
        )
        assertEquals(null, RcsSessionManager.sessionFor("peer@rcs"))
        assertEquals(listOf("at-sign y3"), db.cachedMessageDao().getForConversation("peer@rcs").map { it.body })
        // 'rcs-group:' recipient without participants: not the fan-out branch, no session start.
        assertEquals(
            RcsSendResult.Sent,
            CommunicateRepository.sendRcsMessage(context, "ignored", "colon y3", threadRemoteId = "rcs-group:solo"),
        )
        assertEquals(null, RcsSessionManager.sessionFor("rcs-group:solo"))
        assertEquals(listOf("colon y3"), db.cachedMessageDao().getForConversation("rcs-group:solo").map { it.body })
    }

    /** Loopback MSRP peer: ack each SEND chunk with a 200 response for its txid. */
    private class MsrpPeer {
        val server = ServerSocket(0)
        val received = CopyOnWriteArrayList<String>()
        @Volatile var error: String? = null
        val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 15_000
                    val input = socket.getInputStream()
                    val out = socket.getOutputStream()
                    val buf = StringBuilder()
                    val chunk = ByteArray(4096)
                    while (!buf.contains("\$\r\n")) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.append(String(chunk, 0, n, Charsets.UTF_8))
                    }
                    received += buf.toString()
                    val txid = buf.lineSequence().first().split(" ").getOrNull(1) ?: error("no txid")
                    out.write("MSRP $txid 200 OK\r\n-------$txid\$\r\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }.onFailure { error = it.toString() }
        }.apply { isDaemon = true }

        fun start() = thread.start()
        fun close() = runCatching { server.close() }
    }

    private fun seedSession(recipient: String, remotePath: String) {
        RcsSessionManager.sessionsMutable.value = RcsSessionManager.sessionsMutable.value +
            (recipient to RcsSession(
                dialogId = "dlg-y3", callId = "call-y3", localTag = "local1", remoteTag = "remote1",
                remoteUri = "sip:$recipient@rcs", conversationId = recipient,
                msrpRemotePath = remotePath, msrpLocalPath = "msrp://127.0.0.1:9/local;tcp",
            ))
    }

    @Test
    fun `session MSRP send succeeds over loopback and caches once without pager SIP`() = runTest {
        val peer = MsrpPeer(); peer.start()
        val recipient = "+14155550110"
        seedSession(recipient, "msrp://127.0.0.1:${peer.server.localPort}/sess-y3;tcp")
        val result = CommunicateRepository.sendRcsMessage(context, recipient, "via msrp y3")
        peer.thread.join(15_000); peer.close()
        assertEquals(null, peer.error)
        assertEquals(RcsSendResult.Sent, result)
        assertTrue(peer.received.single().contains("via msrp y3"), "${peer.received}")
        assertEquals(listOf("via msrp y3"), db.cachedMessageDao().getForConversation(recipient).map { it.body })
        // The pager SIP leg was not used for the text.
        assertFalse(sentBodies().any { it.contains("via msrp y3") })
    }

    @Test
    fun `dead MSRP session falls through to pager success`() = runTest {
        val recipient = "+14155550111"
        seedSession(recipient, "msrp://127.0.0.1:1/dead;tcp")
        val result = CommunicateRepository.sendRcsMessage(context, recipient, "pager after msrp y3")
        assertEquals(RcsSendResult.Sent, result)
        assertTrue(sentBodies().any { it.contains("pager after msrp y3") })
        assertEquals(listOf("pager after msrp y3"), db.cachedMessageDao().getForConversation(recipient).map { it.body })
    }

    @Test
    fun `attachment send uses FT envelope via fake SIP and caches file row`() = runTest {
        assertEquals(null, RcsFileTransferHttp.contentServerUri)
        val file = File(context.cacheDir, "y3-photo.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val attachment = CommunicateAttachment(Uri.fromFile(file).toString(), "image/png", "y3-photo.png")
        val result = CommunicateRepository.sendRcsMessage(
            context, "+14155550112", "with photo y3", attachments = listOf(attachment),
        )
        assertEquals(RcsSendResult.Sent, result)
        assertTrue(sentBodies().any { it.contains("FT-MIME: image/png") && it.contains("with photo y3") }, "$sent")
        val rows = db.cachedMessageDao().getForConversation("+14155550112")
        assertEquals(1, rows.size)
        assertEquals("with photo y3", rows.single().body)
        assertEquals(attachment.contentUri, rows.single().ftUrl)
        assertEquals("image/png", rows.single().ftMime)

        // Blank caption: the cached row falls back to the file name.
        val result2 = CommunicateRepository.sendRcsMessage(
            context, "+14155550113", "", attachments = listOf(attachment),
        )
        assertEquals(RcsSendResult.Sent, result2)
        assertEquals(listOf("y3-photo.png"), db.cachedMessageDao().getForConversation("+14155550113").map { it.body })
    }

    @Test
    fun `encrypted conversation fails closed without a usable MLS group`() = runTest {
        val recipient = "+14155550114"
        db.mlsGroupDao().upsert(RcsMlsGroup(groupIdHex = "00ff10ab", conversationId = recipient, storage = byteArrayOf(1)))
        val result = CommunicateRepository.sendRcsMessage(context, recipient, "secret y3")
        assertEquals(RcsSendResult.Failed, result)
        assertFalse(sentBodies().any { it.contains("secret y3") })
        assertTrue(db.cachedMessageDao().getForConversation(recipient).isEmpty())
    }

    @Test
    fun `encrypted send succeeds when a real MLS group can be created`() = runTest {
        val me = "+14155550100"
        val recipient = "+14155550115"
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setLine1Number(me)
        val created = if (RustMlsCrypto.isAvailable) {
            val peerKp = runCatching {
                RcsE2E.identityFor(context, recipient)
                RcsE2E.freshKeyPackage(context, recipient)
            }.getOrNull()
            if (peerKp == null) null else RcsE2E.createEncryptedGroup(context, me, recipient, listOf(peerKp))
        } else {
            null
        }
        if (created == null) {
            // Native MLS unavailable / cannot generate on this host: pin fail-closed instead.
            db.mlsGroupDao().upsert(RcsMlsGroup(groupIdHex = "00ff10ab", conversationId = recipient, storage = byteArrayOf(1)))
            assertEquals(RcsSendResult.Failed, CommunicateRepository.sendRcsMessage(context, recipient, "secret y3"))
            assertFalse(sentBodies().any { it.contains("secret y3") })
            return@runTest
        }
        val result = CommunicateRepository.sendRcsMessage(context, recipient, "secret y3")
        assertEquals(RcsSendResult.Sent, result)
        val mlsSend = sent.firstOrNull { it.second.contains("application/x-rcs-mls") }
        assertNotNull(mlsSend, "no MLS send in $sent")
        assertFalse(mlsSend.third.toString(Charsets.UTF_8).contains("secret y3"))
        assertEquals(listOf("secret y3"), db.cachedMessageDao().getForConversation(recipient).map { it.body })
    }
}
