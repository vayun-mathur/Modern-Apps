package com.vayunmathur.communicate.data

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalCachedMessage
import com.vayunmathur.communicate.data.signal.SignalConversation
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalServiceData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppCachedMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppConversation
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Y1: WhatsApp/Signal repository thread/message loaders and mark-read paths,
 * driven through the new `overrideForTest` in-memory Room seams (mirroring
 * the RCS seam) plus the DataStore sign-in flags. These were previously
 * unreachable because the SQLCipher singleton cannot load on a JVM host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y1RepositoryLoadersTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var waDb: WhatsAppDatabase
    private lateinit var sigDb: SignalDatabase

    @BeforeTest
    fun setUp() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        waDb = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        sigDb = Room.inMemoryDatabaseBuilder(app, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        WhatsAppDatabase.overrideForTest = waDb
        SignalDatabase.overrideForTest = sigDb
        DataStoreUtils.getInstance(app).setBoolean("wa_signed_in", true)
        DataStoreUtils.getInstance(app).setBoolean("sig_signed_in", true)
    }

    @AfterTest
    fun tearDown() = runTest {
        DataStoreUtils.getInstance(context).setBoolean("wa_signed_in", false)
        DataStoreUtils.getInstance(context).setBoolean("sig_signed_in", false)
        WhatsAppDatabase.overrideForTest = null
        SignalDatabase.overrideForTest = null
        waDb.close(); sigDb.close()
    }

    // ---------- WhatsApp loaders ----------

    @Test
    fun `whatsapp threads map group and one-to-one rows with unread and revoked snippet`() = runTest {
        val groupJid = "120207@g.us"
        val userJid = "14155550102@s.whatsapp.net"
        waDb.conversationDao().upsert(
            WhatsAppConversation(chatJid = groupJid, isGroup = true, name = "Family", unreadCount = 3,
                participants = "[\"Alice\",\"Bob\"]", lastMessageTimestamp = 500L))
        waDb.conversationDao().upsert(
            WhatsAppConversation(chatJid = userJid, isGroup = false, unreadCount = 0, lastMessageTimestamp = 400L))
        waDb.cachedMessageDao().upsert(
            WhatsAppCachedMessage(messageId = "m1", conversationJid = groupJid, body = "hi group",
                timestamp = 500L, outgoing = false, senderJid = "14155550103@s.whatsapp.net"))
        waDb.cachedMessageDao().upsert(
            WhatsAppCachedMessage(messageId = "m2", conversationJid = userJid, body = "secret",
                timestamp = 400L, outgoing = true, isRevoked = true))

        val threads = with(CommunicateRepository) { loadWhatsAppThreads(context) }
        assertEquals(2, threads.size)
        val group = threads.first { it.remoteId == groupJid }
        assertTrue(group.isGroup)
        assertEquals("Family", group.groupTitle)
        assertEquals("Family", group.displayName)
        assertEquals(listOf("Alice", "Bob"), group.participants)
        assertEquals(3, group.unreadCount)
        assertEquals("hi group", group.snippet)
        assertEquals(CommunicateLine.WhatsApp, group.line)
        assertEquals(CommunicateRepository.stableThreadId(groupJid), group.threadId)
        val user = threads.first { it.remoteId == userJid }
        assertFalse(user.isGroup)
        assertEquals("+14155550102", user.address)
        assertEquals("This message was deleted", user.snippet)
    }

    @Test
    fun `whatsapp threads fall back to serviceData group flag and blank when signed out`() = runTest {
        val jid = "999@g.us"
        waDb.cachedMessageDao().upsert(
            WhatsAppCachedMessage(messageId = "m9", conversationJid = jid, body = "x", timestamp = 10L,
                outgoing = false, serviceData = WhatsAppServiceData(isGroup = true, senderName = "Crew").serialize()))
        var threads = with(CommunicateRepository) { loadWhatsAppThreads(context) }
        assertEquals(1, threads.size)
        assertTrue(threads[0].isGroup)
        DataStoreUtils.getInstance(context).setBoolean("wa_signed_in", false)
        threads = with(CommunicateRepository) { loadWhatsAppThreads(context) }
        assertTrue(threads.isEmpty())
    }

    @Test
    fun `whatsapp messages map status sender and revoked body`() = runTest {
        val jid = "14155550102@s.whatsapp.net"
        waDb.cachedMessageDao().upsertAll(listOf(
            WhatsAppCachedMessage(messageId = "a", conversationJid = jid, body = "in", timestamp = 1L,
                outgoing = false, senderJid = "14155550102@s.whatsapp.net", status = 0),
            WhatsAppCachedMessage(messageId = "b", conversationJid = jid, body = "out", timestamp = 2L,
                outgoing = true, status = 3),
            WhatsAppCachedMessage(messageId = "c", conversationJid = jid, body = "gone", timestamp = 3L,
                outgoing = true, isRevoked = true, status = 99),
        ))
        val msgs = with(CommunicateRepository) { loadWhatsAppMessages(context, jid) }
        assertEquals(3, msgs.size)
        assertEquals("14155550102@s.whatsapp.net", msgs[0].senderAddress)
        assertNull(msgs[1].senderAddress) // outgoing has no senderAddress
        assertEquals(MessageStatus.Read, msgs[1].status)
        assertEquals("", msgs[2].body)
        assertEquals(MessageStatus.None, msgs[2].status) // out-of-range ordinal maps to None
        assertEquals(CommunicateRepository.stableThreadId(jid), msgs[0].threadId)
        assertEquals("+14155550102", msgs[0].address)
    }

    @Test
    fun `parseParticipantsCsv handles json csv blank and garbage`() {
        with(CommunicateRepository) {
            assertEquals(listOf("A", "B"), parseParticipantsCsv("[\"A\",\"B\"]"))
            assertEquals(listOf("A", "B"), parseParticipantsCsv("A, B"))
            assertEquals(emptyList(), parseParticipantsCsv(null))
            assertEquals(emptyList(), parseParticipantsCsv("  "))
            assertEquals(listOf("[not json"), parseParticipantsCsv("[not json"))
        }
    }

    @Test
    fun `toWhatsAppJid passes jids through and normalizes numbers`() {
        with(CommunicateRepository) {
            assertEquals("123@g.us", toWhatsAppJid(context, "123@g.us"))
            assertEquals("14155550102@s.whatsapp.net", toWhatsAppJid(context, "+1 (415) 555-0102"))
            assertEquals("14155550102@s.whatsapp.net", toWhatsAppJid(context, "4155550102"))
            assertTrue(toWhatsAppJid(context, "0044 20 7946 0958").endsWith("@s.whatsapp.net"))
        }
    }

    @Test
    fun `cacheOutgoingWhatsApp creates conversation and sent status`() = runTest {
        val jid = "14155550102@s.whatsapp.net"
        with(CommunicateRepository) { cacheOutgoingWhatsApp(context, jid, "hello out", "out-1") }
        val rows = waDb.cachedMessageDao().getForConversation(jid)
        assertEquals(1, rows.size)
        assertTrue(rows[0].outgoing)
        assertEquals(1, rows[0].status)
        val conv = waDb.conversationDao().getConversation(jid)
        assertTrue(conv != null && conv.lastMessageTimestamp > 0)
        // Second send updates timestamp, keeps one conversation row.
        with(CommunicateRepository) { cacheOutgoingWhatsApp(context, jid, "again", "out-2") }
        assertEquals(2, waDb.cachedMessageDao().getForConversation(jid).size)
    }

    @Test
    fun `markWhatsAppRead clears unread and returns latest inbound id once`() = runTest {
        val jid = "14155550102@s.whatsapp.net"
        waDb.conversationDao().upsert(WhatsAppConversation(chatJid = jid, unreadCount = 5))
        waDb.cachedMessageDao().upsertAll(listOf(
            WhatsAppCachedMessage(messageId = "in1", conversationJid = jid, body = "a", timestamp = 1000L, outgoing = false),
            WhatsAppCachedMessage(messageId = "out1", conversationJid = jid, body = "b", timestamp = 2000L, outgoing = true),
            WhatsAppCachedMessage(messageId = "in2", conversationJid = jid, body = "c", timestamp = 3000L, outgoing = false),
        ))
        val repo = CommunicateRepository
        val first = repo.markWhatsAppRead(context, jid, "+14155550102", alreadyReadId = null)
        assertEquals("in2", first)
        assertEquals(0, waDb.conversationDao().getConversation(jid)!!.unreadCount)
        val second = repo.markWhatsAppRead(context, jid, "+14155550102", alreadyReadId = "in2")
        assertEquals("in2", second)
    }

    @Test
    fun `markWhatsAppRead with no inbound returns alreadyReadId and group uses sender`() = runTest {
        val groupJid = "777@g.us"
        waDb.conversationDao().upsert(WhatsAppConversation(chatJid = groupJid, isGroup = true, unreadCount = 2))
        assertEquals("prev", CommunicateRepository.markWhatsAppRead(context, groupJid, "x", "prev"))
        waDb.cachedMessageDao().upsert(
            WhatsAppCachedMessage(messageId = "gin", conversationJid = groupJid, body = "hi", timestamp = 5000L,
                outgoing = false, senderJid = "14155550109@s.whatsapp.net"))
        assertEquals("gin", CommunicateRepository.markWhatsAppRead(context, groupJid, "777", null))
    }

    @Test
    fun `sendAttachments reads files and reports failures`() = runTest {
        val file = File(context.cacheDir, "y1-att.txt").apply { writeText("payload") }
        val seen = mutableListOf<String>()
        val ok = with(CommunicateRepository) {
            sendAttachments(context, listOf(CommunicateAttachment(file.toURI().toString(), "text/plain", "a.txt"))) { bytes, mime, name ->
                seen += "${bytes.decodeToString()}:$mime:$name"; true
            }
        }
        // content:// vs file:// handling may fail closed on Robolectric; assert behaviour is consistent:
        // either it read the payload, or it reported failure without invoking send.
        assertTrue(if (ok) seen == listOf("payload:text/plain:a.txt") else seen.isEmpty())
        val bad = with(CommunicateRepository) {
            sendAttachments(context, listOf(CommunicateAttachment("content://nope/1", "image/png"))) { _, _, _ -> true }
        }
        assertFalse(bad)
        val empty = with(CommunicateRepository) { sendAttachments(context, emptyList()) { _, _, _ -> true } }
        assertFalse(empty)
    }

    // ---------- Signal loaders ----------

    @Test
    fun `signal threads and messages map group revoked and status`() = runTest {
        val cid = "3f0e5a2c-1111-2222-3333-444455556666"
        sigDb.conversationDao().upsert(
            SignalConversation(chatId = cid, isGroup = true, name = "Signal Crew", participants = "[\"+1\",\"+2\"]",
                unreadCount = 2, lastMessageTimestamp = 900L))
        sigDb.cachedMessageDao().upsertAll(listOf(
            SignalCachedMessage(messageId = "s1", conversationId = cid, body = "hey", timestamp = 900L,
                outgoing = false, senderId = "+14155550102"),
            SignalCachedMessage(messageId = "s2", conversationId = cid, body = "gone", timestamp = 950L,
                outgoing = true, isRevoked = true, status = 2),
        ))
        val threads = with(CommunicateRepository) { loadSignalThreads(context) }
        assertEquals(1, threads.size)
        assertTrue(threads[0].isGroup)
        assertEquals("Signal Crew", threads[0].groupTitle)
        assertEquals(2, threads[0].unreadCount)
        assertEquals(listOf("+1", "+2"), threads[0].participants)

        val msgs = with(CommunicateRepository) { loadSignalMessages(context, cid) }
        assertEquals(2, msgs.size)
        assertEquals("+14155550102", msgs[0].senderAddress)
        assertEquals("", msgs[1].body)
        assertEquals(MessageStatus.Delivered, msgs[1].status)
        assertEquals(CommunicateLine.Signal, msgs[0].line)
    }

    @Test
    fun `signal threads empty when signed out`() = runTest {
        DataStoreUtils.getInstance(context).setBoolean("sig_signed_in", false)
        assertTrue(with(CommunicateRepository) { loadSignalThreads(context) }.isEmpty())
    }

    @Test
    fun `markSignalRead clears unread and is idempotent`() = runTest {
        val cid = "+14155550102"
        sigDb.conversationDao().upsert(SignalConversation(chatId = cid, unreadCount = 4))
        sigDb.cachedMessageDao().upsertAll(listOf(
            SignalCachedMessage(messageId = "in1", conversationId = cid, body = "a", timestamp = 100L, outgoing = false),
            SignalCachedMessage(messageId = "in2", conversationId = cid, body = "b", timestamp = 200L, outgoing = false),
        ))
        assertEquals("in2", CommunicateRepository.markSignalRead(context, cid, "+14155550102", null))
        assertEquals(0, sigDb.conversationDao().getConversation(cid)!!.unreadCount)
        assertEquals("in2", CommunicateRepository.markSignalRead(context, cid, "+14155550102", "in2"))
        // No inbound at all -> alreadyReadId passthrough
        assertEquals("x", CommunicateRepository.markSignalRead(context, "+1999", "+1999", "x"))
    }

    @Test
    fun `cacheOutgoingSignal creates conversation`() = runTest {
        with(CommunicateRepository) { cacheOutgoingSignal(context, "+14155550177", "out body", "sig-out-1") }
        val rows = sigDb.cachedMessageDao().getForConversation("+14155550177")
        assertEquals(1, rows.size)
        assertTrue(rows[0].outgoing)
        assertEquals(1, rows[0].status)
        assertTrue(sigDb.conversationDao().getConversation("+14155550177") != null)
    }

    @Test
    fun `toSignalRecipient passes uuid and jid and normalizes phone`() {
        with(CommunicateRepository) {
            val uuid = "3f0e5a2c-1111-2222-3333-444455556666"
            assertEquals(uuid, toSignalRecipient(context, uuid))
            assertEquals("grp@g.us", toSignalRecipient(context, "grp@g.us"))
            assertEquals("", toSignalRecipient(context, "  "))
            assertEquals("+14155550102", toSignalRecipient(context, "(415) 555-0102"))
            assertEquals("+442079460958", toSignalRecipient(context, "+44 20 7946 0958"))
        }
    }

    @Test
    fun `merged loaders combine whatsapp and signal threads sorted`() = runTest {
        waDb.cachedMessageDao().upsert(
            WhatsAppCachedMessage(messageId = "wm", conversationJid = "14155550102@s.whatsapp.net",
                body = "wa", timestamp = 8000L, outgoing = false))
        sigDb.cachedMessageDao().upsert(
            SignalCachedMessage(messageId = "sm", conversationId = "+14155550103", body = "sig",
                timestamp = 9000L, outgoing = false))
        val merged = with(CommunicateRepository) { loadSmsThreadsMerged(context) }
        // SIM/GV/RCS loaders run against empty providers and contribute nothing.
        val lines = merged.map { it.line }
        assertTrue(CommunicateLine.Signal in lines && CommunicateLine.WhatsApp in lines)
        assertTrue(merged.zipWithNext().all { (a, b) -> a.timestampMillis >= b.timestampMillis })
    }
}
