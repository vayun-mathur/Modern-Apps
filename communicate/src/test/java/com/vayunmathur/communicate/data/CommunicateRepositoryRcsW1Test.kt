package com.vayunmathur.communicate.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.ImdnDisposition
import com.vayunmathur.communicate.data.rcs.RcsCachedMessage
import com.vayunmathur.communicate.data.rcs.RcsConversation
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W1: RCS repository cache + send helpers in `CommunicateRepositoryRcsKt`,
 * driven through the `RcsDatabase.overrideForTest` in-memory seam and the
 * SIP transport's fake sender (no live carrier).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunicateRepositoryRcsW1Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: RcsDatabase
    private val sent = CopyOnWriteArrayList<Triple<String, String, ByteArray>>()
    private var sendResult = true

    @BeforeTest
    fun setUp() {
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
        RcsDatabase.overrideForTest = null
        db.close()
    }

    private suspend fun seedConversation(id: String, unread: Int = 0, group: Boolean = false) {
        db.conversationDao().upsert(
            RcsConversation(
                remoteId = id, isGroup = group,
                name = if (group) "Team" else null,
                participants = if (group) "[\"+14155550102\",\"+14155550103\"]" else "",
                unreadCount = unread, lastMessageTimestamp = 100L,
            ),
        )
    }

    private suspend fun seedMessage(
        id: String, conv: String, body: String = "hi", outgoing: Boolean = false,
        timestamp: Long = 1_000L, status: Int = 0, imdnId: String = "",
        ftUrl: String? = null, senderId: String = "",
    ) {
        db.cachedMessageDao().upsert(
            RcsCachedMessage(
                messageId = id, conversationId = conv, body = body, timestamp = timestamp,
                outgoing = outgoing, senderId = senderId, ftUrl = ftUrl,
                ftMime = if (ftUrl != null) "image/png" else null, status = status, imdnId = imdnId,
            ),
        )
    }

    @Test
    fun `cacheOutgoing and cacheInbound update conversation timestamps`() = runTest {
        with(CommunicateRepository) {
            cacheOutgoingRcs(context, "+14155550102", "out one", "out-1")
            cacheInboundRcs(context, "+14155550102", "in one", senderId = "+14155550102", messageId = "in-1")
        }
        val msgs = db.cachedMessageDao().getForConversation("+14155550102")
        assertEquals(2, msgs.size)
        assertTrue(msgs.first { it.messageId == "out-1" }.outgoing)
        assertEquals(1, msgs.first { it.messageId == "out-1" }.status)
        val conv = db.conversationDao().getConversation("+14155550102")
        assertNotNull(conv)
        assertEquals(1, conv.unreadCount) // only the inbound row bumps unread
        assertTrue(conv.lastMessageTimestamp > 100L)
    }

    @Test
    fun `cacheInbound dedupes unread bump on redelivery`() = runTest {
        with(CommunicateRepository) {
            cacheInboundRcs(context, "c1", "hello", messageId = "in-9")
            cacheInboundRcs(context, "c1", "hello", messageId = "in-9")
        }
        assertEquals(1, db.conversationDao().getConversation("c1")!!.unreadCount)
        assertEquals(1, db.cachedMessageDao().getForConversation("c1").size)
    }

    @Test
    fun `cacheOutgoingFile stores transfer metadata`() = runTest {
        with(CommunicateRepository) {
            cacheOutgoingRcsFile(context, "c1", "photo", "ft-1", "https://x/f.png", "image/png")
        }
        val row = db.cachedMessageDao().get("ft-1")
        assertNotNull(row)
        assertTrue(row.outgoing)
        assertEquals("https://x/f.png", row.ftUrl)
        assertEquals(1, row.status)
    }

    @Test
    fun `loadRcsMessages maps attachments senders and status`() = runTest {
        seedMessage("m1", "c1", body = "plain", timestamp = 10L)
        seedMessage(
            "m2", "c1", body = "photo", outgoing = true, timestamp = 20L, status = 99,
            ftUrl = "https://x/f.png", senderId = "+14155550102",
        )
        seedMessage("m3", "c1", body = "from bob", timestamp = 30L, senderId = "+14155550103")
        val msgs = CommunicateRepository.loadRcsMessages(context, "c1")
        assertEquals(3, msgs.size)
        assertEquals(CommunicateLine.Rcs, msgs[0].line)
        assertTrue(msgs[0].attachments.isEmpty())
        assertEquals(1, msgs[1].attachments.size)
        assertEquals("image/png", msgs[1].attachments[0].mimeType)
        assertEquals(MessageStatus.None, msgs[1].status) // out-of-range ordinal
        assertNull(msgs[1].senderAddress) // outgoing rows hide the sender
        assertEquals("+14155550103", msgs[2].senderAddress)
        assertTrue(CommunicateRepository.loadRcsMessages(context, "missing").isEmpty())
    }

    @Test
    fun `loadRcsThreads maps groups and one-to-one chats`() = runTest {
        seedConversation("+14155550102", unread = 2)
        seedConversation("rcs-group:1", group = true)
        seedMessage("m1", "+14155550102", body = "latest 1:1", timestamp = 50L)
        seedMessage("m2", "rcs-group:1", body = "latest group", timestamp = 60L)
        val threads = CommunicateRepository.loadRcsThreads(context)
        assertEquals(2, threads.size)
        val single = threads.first { it.remoteId == "+14155550102" }
        assertFalse(single.isGroup)
        assertEquals("latest 1:1", single.snippet)
        assertEquals(2, single.unreadCount)
        assertEquals(CommunicateLine.Rcs, single.line)
        val group = threads.first { it.remoteId == "rcs-group:1" }
        assertTrue(group.isGroup)
        assertEquals("Team", group.groupTitle)
        assertEquals(listOf("+14155550102", "+14155550103"), group.participants)
    }

    @Test
    fun `markRcsRead clears unread and sends display reports once`() = runTest {
        seedConversation("c1", unread = 3)
        seedMessage("in-1", "c1", imdnId = "imdn-1")
        seedMessage("out-1", "c1", outgoing = true)
        CommunicateRepository.markRcsRead(context, "c1", "+14155550102")
        assertEquals(0, db.conversationDao().getConversation("c1")!!.unreadCount)
        assertEquals(1, sent.size) // one display report for the inbound row
        assertTrue(sent.single().second.contains("message/cpim") || sent.single().first.contains("MESSAGE"))
        CommunicateRepository.markRcsRead(context, "c1", "+14155550102")
        assertEquals(1, sent.size) // already reported in this process
    }

    @Test
    fun `markRcsRead resolves recipient from address`() = runTest {
        seedConversation("+14155550102", unread = 1)
        CommunicateRepository.markRcsRead(context, null, "4155550102")
        assertEquals(0, db.conversationDao().getConversation("+14155550102")!!.unreadCount)
        CommunicateRepository.markRcsRead(context, " ", "4155550102")
        assertEquals(0, db.conversationDao().getConversation("+14155550102")!!.unreadCount)
    }

    @Test
    fun `applyRcsDeliveryReport advances status ticks`() = runTest {
        seedMessage("m1", "c1", outgoing = true, status = 1)
        seedMessage("m2", "c1", outgoing = true, status = 1)
        seedMessage("m3", "c1", outgoing = true, status = 1)
        with(CommunicateRepository) {
            applyRcsDeliveryReport(context, "m1", ImdnDisposition.Delivered)
            applyRcsDeliveryReport(context, "m2", ImdnDisposition.Displayed)
            applyRcsDeliveryReport(context, "m3", ImdnDisposition.Failed)
            applyRcsDeliveryReport(context, "missing", ImdnDisposition.Failed)
        }
        assertEquals(2, db.cachedMessageDao().get("m1")!!.status)
        assertEquals(3, db.cachedMessageDao().get("m2")!!.status)
        assertEquals(4, db.cachedMessageDao().get("m3")!!.status)
    }

    @Test
    fun `sendRcsMessage rejects blank recipients and falls back when incapable`() = runTest {
        assertEquals(
            RcsSendResult.Failed,
            CommunicateRepository.sendRcsMessage(context, " ", "hi"),
        )
        // No valid IMS subscription on the test host: capability fails closed.
        assertEquals(
            RcsSendResult.FallbackSms,
            CommunicateRepository.sendRcsMessage(context, "+14155550102", "hi"),
        )
        assertEquals(
            RcsSendResult.FallbackSms,
            CommunicateRepository.sendRcsMessage(
                context, "+14155550102", "hi",
                participants = listOf("+14155550102", "+14155550103"),
            ),
        )
    }

    @Test
    fun `geopush sends and caches the location echo`() = runTest {
        assertTrue(
            CommunicateRepository.sendRcsGeopush(context, "+14155550102", 37.4, -122.1, "Office"),
        )
        assertEquals(1, sent.size)
        assertTrue(sent.single().second.contains("geopush"))
        val cached = db.cachedMessageDao().getForConversation("+14155550102")
        assertEquals(1, cached.size)
        assertEquals("Office", cached.single().body)
        sendResult = false
        assertFalse(
            CommunicateRepository.sendRcsGeopush(context, "+14155550102", 0.0, 0.0),
        )
    }

    @Test
    fun `edit and revoke only update the cache when the SIP leg accepts`() = runTest {
        seedMessage("m1", "c1", body = "original", outgoing = true)
        assertTrue(CommunicateRepository.editRcsMessage(context, "c1", "m1", "edited"))
        assertEquals("edited", db.cachedMessageDao().get("m1")!!.body)
        assertFalse(CommunicateRepository.editRcsMessage(context, "c1", "m1", " "))
        sendResult = false
        assertFalse(CommunicateRepository.editRcsMessage(context, "c1", "m1", "again"))
        assertEquals("edited", db.cachedMessageDao().get("m1")!!.body)
        sendResult = true
        assertTrue(CommunicateRepository.revokeRcsMessage(context, "c1", "m1"))
        assertEquals("", db.cachedMessageDao().get("m1")!!.body)
        // A revoke for a row we no longer hold still sends the SIP report.
        assertTrue(CommunicateRepository.revokeRcsMessage(context, "c1", "missing"))
    }

    @Test
    fun `hasRcsE2eGroup and encryptForE2E are empty without an MLS group`() = runTest {
        with(CommunicateRepository) {
            assertFalse(hasRcsE2eGroup(context, "+14155550102"))
            assertNull(encryptForE2E(context, "+14155550102", "hi"))
            assertNull(encryptForE2E(context, "+14155550102", " "))
        }
    }

    @Test
    fun `deleteConversation removes rcs rows through the override db`() = runTest {
        seedConversation("c-del")
        seedMessage("m1", "c-del")
        val thread = SmsThread(
            threadId = 1L, address = "c-del", displayName = null, snippet = "",
            timestampMillis = 0L, unreadCount = 0, line = CommunicateLine.Rcs, remoteId = "c-del",
        )
        assertTrue(CommunicateRepository.deleteConversation(context, thread))
        assertNull(db.conversationDao().getConversation("c-del"))
        assertTrue(db.cachedMessageDao().getForConversation("c-del").isEmpty())
    }
}
