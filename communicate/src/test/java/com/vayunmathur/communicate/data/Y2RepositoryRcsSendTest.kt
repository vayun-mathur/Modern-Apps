package com.vayunmathur.communicate.data

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.Telephony
import android.telephony.ims.SipMessage
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsCachedMessage
import com.vayunmathur.communicate.data.rcs.RcsConversation
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsDirectSip
import com.vayunmathur.communicate.data.rcs.RcsOutbox
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.buildEditBody
import com.vayunmathur.communicate.data.rcs.buildGeopushBody
import com.vayunmathur.communicate.data.rcs.buildRevokeBody
import com.vayunmathur.communicate.data.rcs.chunkLargeMessage
import com.vayunmathur.communicate.data.rcs.parseChunkHeader
import com.vayunmathur.communicate.data.rcs.parseEditBody
import com.vayunmathur.communicate.data.rcs.parseGeopushBody
import com.vayunmathur.communicate.data.rcs.parseRevokeBody
import com.vayunmathur.communicate.data.rcs.reassembleLargeMessage
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Y2 wave-1: the RCS send stack in `CommunicateRepositoryRcsKt` /
 * `CommunicateRepositorySendKt`, the SIP transport (`RcsSipTransportKt`),
 * the direct-SIP leg (`RcsDirectSipKt`) and the UP body builders.
 *
 * Driven through the same seams as W1 (`RcsDatabase.overrideForTest`,
 * `RcsSipTransport.fakeSenderForTest`). The UCE capability query and the IMS
 * PDN have no JVM doubles, so `sendRcsMessage`'s Sent leg is unreachable
 * here by construction: every capable-path assert below pins the fail-closed
 * FallbackSms/Failed contract instead, and the Sent behaviour is asserted at
 * the transport level (fake sender) via geopush/edit/revoke, which share the
 * same `sendSipMessage` + cache code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [W1SmsManagerShadow::class])
class Y2RepositoryRcsSendTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: RcsDatabase
    private val sent = CopyOnWriteArrayList<Triple<String, String, ByteArray>>()
    private var sendResult = true

    @BeforeTest
    fun setUp() {
        FakeTelephonyState.reset()
        FakeContactsState.reset()
        Robolectric.setupContentProvider(FakeSmsProvider::class.java, "sms")
        Robolectric.setupContentProvider(FakeMmsProvider::class.java, "mms")
        Robolectric.setupContentProvider(FakeMmsSmsProvider::class.java, "mms-sms")
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, android.provider.ContactsContract.AUTHORITY)
        db = Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = db
        sent.clear()
        sendResult = true
        RcsSipTransport.fakeSenderForTest = { startLine, headers, body ->
            sent += Triple(startLine, headers, body.copyOf())
            sendResult
        }
    }

    @AfterTest
    fun tearDown() {
        RcsSipTransport.fakeSenderForTest = null
        RcsSipTransport.installConnectionForTest(null, -1L, null)
        RcsDatabase.overrideForTest = null
        db.close()
    }

    private suspend fun seedMessage(id: String, conv: String, body: String, outgoing: Boolean = true) {
        db.cachedMessageDao().upsert(
            RcsCachedMessage(
                messageId = id, conversationId = conv, body = body, timestamp = 1_000L,
                outgoing = outgoing, status = 1,
            ),
        )
    }

    // ---- recipient + SIP construction ----

    @Test
    fun `toRcsRecipient passes URIs through and normalizes plain numbers`() {
        with(CommunicateRepository) {
            assertEquals("", toRcsRecipient(context, "  "))
            assertEquals("sip:+14155550102@rcs", toRcsRecipient(context, "sip:+14155550102@rcs"))
            assertEquals("rcs-group:1,2", toRcsRecipient(context, "rcs-group:1,2"))
            assertEquals("user@host", toRcsRecipient(context, "user@host"))
            assertEquals("+14155550102", toRcsRecipient(context, "(415) 555-0102"))
            assertEquals("+442079460958", toRcsRecipient(context, "+44 20 7946 0958"))
        }
    }

    @Test
    fun `buildChatMessage frames a CPIM pager message`() {
        val (startLine, headers, body) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs", toUri = "sip:+14155550102@rcs",
            callId = "call-y2@rcs", body = "hello rcs",
        )
        assertEquals("MESSAGE sip:+14155550102@rcs SIP/2.0", startLine)
        assertTrue(headers.contains("To: <sip:+14155550102@rcs>"), headers)
        assertTrue(headers.contains("Call-ID: call-y2@rcs"), headers)
        assertTrue(headers.contains("Content-Type: message/cpim"), headers)
        assertTrue(headers.contains("branch=z9hG4bK"), headers)
        assertTrue(headers.contains("Content-Length: ${body.size}"), headers)
        val text = body.toString(Charsets.UTF_8)
        assertTrue(text.contains("hello rcs"), text)
        assertTrue(text.contains("imdn.Message-ID: rcs-"), text)
        assertTrue(text.contains("positive-delivery, display"), text)
    }

    @Test
    fun `buildCpimBody counts UTF-8 bytes not chars`() {
        val cpim = RcsSipTransport.buildCpimBody("héllo 👋")
        assertTrue(cpim.contains("Content-Length: ${"héllo 👋".toByteArray(Charsets.UTF_8).size}"), cpim)
        assertTrue(cpim.endsWith("héllo 👋"), cpim)
        assertTrue(cpim.contains("Content-Type: text/plain;charset=UTF-8"), cpim)
    }

    // ---- UP body builders ----

    @Test
    fun `geopush edit and revoke bodies round trip`() {
        val geo = buildGeopushBody(37.422, -122.084, "A & B < HQ")
        assertTrue(geo.contains("A &amp; B &lt; HQ"), geo) // label is XML-escaped on the wire
        val parsed = parseGeopushBody(geo)
        assertNotNull(parsed)
        assertEquals(37.422, parsed.first)
        assertEquals(-122.084, parsed.second)
        assertNull(parseGeopushBody("plain text"))
        assertNull(parseGeopushBody("<rcspushlocation><latitude>x</latitude></rcspushlocation>"))
        assertEquals("m-42", parseRevokeBody(buildRevokeBody("m-42")))
        assertNull(parseRevokeBody("nothing here"))
        assertEquals("m-7" to "new text", parseEditBody(buildEditBody("m-7", "new text")))
        assertNull(parseEditBody("imdn.Message-ID: m-7\r\n\r\nno edit marker"))
    }

    @Test
    fun `large message chunking tags parts and reassembles only when complete`() {
        assertEquals(listOf("short"), chunkLargeMessage("id-1", "short"))
        val big = "x".repeat(20_000)
        val chunks = chunkLargeMessage("id-big", big)
        assertTrue(chunks.size >= 3, "chunks=${chunks.size}")
        val parts = chunks.mapIndexed { i, c ->
            val header = parseChunkHeader(c)
            assertNotNull(header)
            assertEquals("id-big", header.first)
            assertEquals(i + 1, header.second)
            assertEquals(chunks.size, header.third)
            (i + 1) to c.substringAfter("\r\n\r\n")
        }.toMap()
        assertEquals(big, reassembleLargeMessage(parts, chunks.size))
        assertNull(reassembleLargeMessage(parts - 1, chunks.size))
        assertNull(parseChunkHeader("no headers here"))
    }

    // ---- transport ----

    @Test
    fun `transport canSend follows the fake sender seam and activeLeg stays none`() = runTest {
        assertTrue(RcsSipTransport.canSend()) // fake installed in setUp
        assertEquals("none", RcsSipTransport.activeLeg)
        assertNull(RcsSipTransport.lastConfigSnapshot())
        val ok = RcsSipTransport.sendSipMessage("MESSAGE sip:x@rcs SIP/2.0", "h: v\r\n", "b".toByteArray())
        assertTrue(ok)
        assertEquals(1, sent.size)
        sendResult = false
        assertFalse(RcsSipTransport.sendSipMessage("MESSAGE sip:x@rcs SIP/2.0", "h: v\r\n", "b".toByteArray()))
        assertEquals(2, sent.size)
        RcsSipTransport.fakeSenderForTest = null
        assertFalse(RcsSipTransport.canSend())
        assertFalse(RcsSipTransport.sendSipMessage("MESSAGE sip:x@rcs SIP/2.0", "h: v\r\n", "b".toByteArray()))
        // cleanupSession / tearDown are silent no-ops without a delegate.
        RcsSipTransport.cleanupSession("")
        RcsSipTransport.cleanupSession("call-1")
        RcsSipTransport.tearDown(null)
    }

    @Test
    fun `installConnectionForTest drives the registration state flow`() {
        RcsSipTransport.installConnectionForTest(null, -1L, null)
        assertEquals(RcsRegistrationState.Unknown, RcsSipTransport.state.value)
        assertFalse(RcsSipTransport.state.value is RcsRegistrationState.Available)
    }

    @Test
    fun `sip responses without a pending send are stray-routed by status`() = runTest {
        fun response(code: Int): SipMessage {
            val headers = "Via: SIP/2.0/TCP local;branch=z9hG4bKzz$code\r\n" +
                "Call-ID: stray-$code@rcs\r\nCSeq: 1 MESSAGE\r\nContent-Length: 0\r\n"
            return SipMessage("SIP/2.0 $code Reason", headers, ByteArray(0))
        }
        // 2xx with no transaction: not consumed as a MESSAGE, not a dialog final.
        assertFalse(RcsSipTransport.onSipResponseMessage(response(200)))
        // Provisional with no pending send: absorbed by nobody.
        assertFalse(RcsSipTransport.onSipResponseMessage(response(100)))
        // Final failures with no transaction: routed to the session layer by Call-ID.
        assertTrue(RcsSipTransport.onSipResponseMessage(response(404)))
        assertTrue(RcsSipTransport.onSipResponseMessage(response(486)))
    }

    // ---- sendRcsMessage contracts (capability fails closed on this host) ----

    @Test
    fun `sendRcsMessage rejects blanks and falls back for incapable contacts`() = runTest {
        with(CommunicateRepository) {
            assertEquals(RcsSendResult.Failed, sendRcsMessage(context, "", "hi"))
            assertEquals(RcsSendResult.Failed, sendRcsMessage(context, " ", "hi", threadRemoteId = " "))
            assertEquals(RcsSendResult.FallbackSms, sendRcsMessage(context, "+14155550102", "hi"))
            assertEquals(
                RcsSendResult.FallbackSms,
                sendRcsMessage(context, "+14155550102", "hi", threadRemoteId = "rcs-group:1",
                    participants = listOf("+14155550102", "+14155550103")),
            )
            assertEquals(
                RcsSendResult.FallbackSms,
                sendRcsMessage(context, "+14155550102", "hi",
                    attachments = listOf(CommunicateAttachment("content://x/1", "image/png"))),
            )
        }
        // Nothing reached the SIP leg and nothing was cached for the fallback path.
        assertTrue(sent.isEmpty())
        assertTrue(db.cachedMessageDao().getForConversation("+14155550102").isEmpty())
    }

    @Test
    fun `geopush caches the label echo and only on transport success`() = runTest {
        assertTrue(CommunicateRepository.sendRcsGeopush(context, "+14155550102", 37.4, -122.1, "Office"))
        assertEquals(1, sent.size)
        assertTrue(sent.single().second.contains("geopush"), sent.single().second)
        var rows = db.cachedMessageDao().getForConversation("+14155550102")
        assertEquals(listOf("Office"), rows.map { it.body })
        assertTrue(rows.single().outgoing)

        // No label: the cached echo is the location placeholder.
        assertTrue(CommunicateRepository.sendRcsGeopush(context, "+14155550102", 1.0, 2.0))
        rows = db.cachedMessageDao().getForConversation("+14155550102")
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.body == "📍 Location" })

        sendResult = false
        assertFalse(CommunicateRepository.sendRcsGeopush(context, "+14155550102", 1.0, 2.0, "Nope"))
        assertEquals(2, db.cachedMessageDao().getForConversation("+14155550102").size)

        RcsSipTransport.fakeSenderForTest = null
        assertFalse(CommunicateRepository.sendRcsGeopush(context, "+14155550102", 1.0, 2.0))
    }

    @Test
    fun `edit and revoke mutate the cache only when the SIP leg accepts`() = runTest {
        seedMessage("m1", "c1", "original")
        assertTrue(CommunicateRepository.editRcsMessage(context, "c1", "m1", "edited"))
        assertEquals("edited", db.cachedMessageDao().get("m1")!!.body)
        assertTrue(sent.single().third.toString(Charsets.UTF_8).contains("edited"))

        val before = sent.size
        assertFalse(CommunicateRepository.editRcsMessage(context, "c1", "m1", "   "))
        assertEquals(before, sent.size) // blank edits never reach the wire

        sendResult = false
        assertFalse(CommunicateRepository.editRcsMessage(context, "c1", "m1", "again"))
        assertFalse(CommunicateRepository.revokeRcsMessage(context, "c1", "m1"))
        assertEquals("edited", db.cachedMessageDao().get("m1")!!.body)

        sendResult = true
        assertTrue(CommunicateRepository.revokeRcsMessage(context, "c1", "m1"))
        assertEquals("", db.cachedMessageDao().get("m1")!!.body)
        // Revoking a row we no longer hold still sends the report.
        assertTrue(CommunicateRepository.revokeRcsMessage(context, "c1", "missing"))

        RcsSipTransport.fakeSenderForTest = null
        assertFalse(CommunicateRepository.editRcsMessage(context, "c1", "m1", "x"))
        assertFalse(CommunicateRepository.revokeRcsMessage(context, "c1", "m1"))
    }

    // ---- outbox (§4.3 deferred retry) ----

    @Test
    fun `outbox enqueues retries with backoff and expires into a failed row`() = runTest {
        assertNull(RcsOutbox.enqueue(context, "c1", "  "))
        val id = RcsOutbox.enqueue(context, "+14155550102", "parked hello")
        assertNotNull(id)
        assertEquals(1, RcsOutbox.pendingCount(context))

        // Not yet due: the pump leaves it alone.
        val (retried0, sent0) = RcsOutbox.pumpDue(context, CommunicateRepository)
        assertEquals(0, retried0)
        assertEquals(0, sent0)

        // Force due + expired: pump deletes the row and marks the message failed.
        db.outboxDao().upsert(
            db.outboxDao().dueForRetry(Long.MAX_VALUE).first().copy(
                nextRetryMs = 0L, expiryMs = 1L,
            ),
        )
        val (retried1, sent1) = RcsOutbox.pumpDue(context, CommunicateRepository)
        assertEquals(1, retried1)
        assertEquals(0, sent1)
        assertEquals(0, RcsOutbox.pendingCount(context))
        val failed = db.cachedMessageDao().get(id)
        assertNotNull(failed)
        assertEquals(4, failed.status)
        assertEquals("parked hello", failed.body)
    }

    @Test
    fun `outbox retry bumps attempts when the resend falls back`() = runTest {
        val id = RcsOutbox.enqueue(context, "+14155550102", "retry me")!!
        db.outboxDao().upsert(
            db.outboxDao().dueForRetry(Long.MAX_VALUE).first().copy(nextRetryMs = 0L),
        )
        val (retried, sentCount) = RcsOutbox.pumpDue(context, CommunicateRepository)
        assertEquals(1, retried)
        assertEquals(0, sentCount) // incapable host: resend falls back, row stays parked
        val all = db.outboxDao().dueForRetry(Long.MAX_VALUE)
        assertTrue(all.any { it.messageId == id && it.attempts == 1 }, "rows=$all")
    }

    // ---- direct SIP leg ----

    @Test
    fun `direct sip refuses invalid subscriptions and sends nothing unregistered`() = runTest {
        assertFalse(RcsDirectSip.start(context, -1))
        assertFalse(RcsDirectSip.isRegistered())
        assertFalse(RcsDirectSip.sendMessage("MESSAGE sip:x@rcs SIP/2.0", "h: v\r\n", "b".toByteArray()))
        RcsDirectSip.onInboundMessage = { _, _, _ -> }
        RcsDirectSip.stop()
        RcsDirectSip.stop() // idempotent
        assertFalse(RcsDirectSip.isRegistered())
    }

    // ---- unified send dispatch ----

    @Test
    fun `sendMessage routes sim groups to MMS and rcs groups to the MMS fallback`() = runTest {
        shadowOf(context as Application).denyPermissions(Manifest.permission.SEND_SMS)
        // SIM group: MMS requires SEND_SMS, so this fails rather than opening a composer.
        assertFalse(
            CommunicateRepository.sendMessage(
                context, LineChoice.Sim(-1, "SIM"), "+14155550102", "group hi",
                participants = listOf("+14155550102", "+14155550103"),
            ),
        )
        // RCS group on an incapable host: fallback is MMS-shaped too, same failure.
        assertFalse(
            CommunicateRepository.sendMessage(
                context, LineChoice.Rcs, "+14155550102", "group hi",
                participants = listOf("+14155550102", "+14155550103"),
            ),
        )
        // RCS 1:1 fallback is a plain SMS: without permission it opens the composer.
        assertTrue(
            CommunicateRepository.sendMessage(context, LineChoice.Rcs, "+14155550102", "one to one"),
        )
        val intent = shadowOf(context as Application).nextStartedActivity
        assertNotNull(intent)
        assertEquals(android.content.Intent.ACTION_SENDTO, intent.action)
    }

    @Test
    fun `sendSimMms with permission composes persists and reports success`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.SEND_SMS)
        val ok = with(CommunicateRepository) {
            sendSimMms(context, -1, listOf("+14155550102", "+14155550103"), "mms hi", emptyList())
        }
        assertTrue(ok, "MMS send should succeed against the fake providers")
        val uris = FakeTelephonyState.inserts.map { it.first.toString() }
        assertTrue(uris.any { it == "content://mms" }, "mms row inserted: $uris")
        assertTrue(uris.any { it.contains("/addr") }, "addr rows inserted: $uris")
    }

    @Test
    fun `persistOutgoingMms without a body writes addresses but no text part`() {
        with(CommunicateRepository) {
            persistOutgoingMms(context, 2, listOf("+14155550102"), "")
        }
        val uris = FakeTelephonyState.inserts.map { it.first.toString() }
        assertTrue(uris.any { it == "content://mms" }, "uris=$uris")
        assertFalse(uris.any { it.contains("/part") }, "no text part for a blank body: $uris")
        assertEquals(1, FakeTelephonyState.inserts.count { it.first.toString().contains("/addr") })
        val mmsRow = FakeTelephonyState.inserts.first { it.first.toString() == "content://mms" }.second
        assertEquals(2, mmsRow?.getAsInteger(Telephony.Mms.SUBSCRIPTION_ID))
    }

    @Test
    fun `merged message loader reads the RCS cache for RCS threads`() = runTest {
        db.conversationDao().upsert(RcsConversation(remoteId = "+14155550102"))
        seedMessage("rm1", "+14155550102", "rcs merged body", outgoing = false)
        val thread = SmsThread(
            threadId = 9L, address = "+14155550102", displayName = null, snippet = "",
            timestampMillis = 0L, unreadCount = 0, line = CommunicateLine.Rcs,
            remoteId = "+14155550102",
        )
        val msgs = CommunicateRepository.loadSmsMessagesMerged(context, thread)
        assertEquals(listOf("rcs merged body"), msgs.map { it.body })
        assertEquals(CommunicateLine.Rcs, msgs.single().line)

        val threads = CommunicateRepository.loadSmsThreadsMerged(context)
        val rcsThread = threads.firstOrNull { it.line == CommunicateLine.Rcs }
        assertNotNull(rcsThread)
        assertEquals("+14155550102", rcsThread.remoteId)
        assertEquals("rcs merged body", rcsThread.snippet)
    }
}
