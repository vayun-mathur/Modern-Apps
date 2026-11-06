package com.vayunmathur.communicate.whatsapp

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAvatarCache
import com.vayunmathur.communicate.data.whatsapp.WhatsAppCachedMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppCachedReaction
import com.vayunmathur.communicate.data.whatsapp.WhatsAppCallLog
import com.vayunmathur.communicate.data.whatsapp.WhatsAppContact
import com.vayunmathur.communicate.data.whatsapp.WhatsAppConversation
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDevice
import com.vayunmathur.communicate.data.whatsapp.WhatsAppE2EIdentity
import com.vayunmathur.communicate.data.whatsapp.WhatsAppE2EPreKey
import com.vayunmathur.communicate.data.whatsapp.WhatsAppE2ESenderKey
import com.vayunmathur.communicate.data.whatsapp.WhatsAppE2ESession
import com.vayunmathur.communicate.data.whatsapp.WhatsAppE2ESignedPreKey
import com.vayunmathur.communicate.data.whatsapp.WhatsAppMediaRequest
import com.vayunmathur.communicate.data.whatsapp.WhatsAppPollOption
import com.vayunmathur.communicate.data.whatsapp.WhatsAppPollSecret
import com.vayunmathur.communicate.data.whatsapp.WhatsAppSession
import com.vayunmathur.communicate.data.whatsapp.WhatsAppTypeConverters
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): WhatsAppDatabase DAOs against an in-memory database.
 * Covers conversations, devices/sessions, media requests, avatar cache, poll
 * options/secrets, E2E stores, cached messages/reactions, contacts, call log,
 * and the string-list type converters.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice1DatabaseTest {

    private lateinit var db: WhatsAppDatabase

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WhatsAppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @AfterTest
    fun close() {
        db.close()
    }

    @Test
    fun conversation_upsertGetUpdateDelete() = runTest {
        val dao = db.conversationDao()
        assertNull(dao.getConversation("1@s.whatsapp.net"))
        dao.upsert(WhatsAppConversation(chatJid = "1@s.whatsapp.net", lastMessageTimestamp = 10))
        assertEquals(10L, dao.getConversation("1@s.whatsapp.net")!!.lastMessageTimestamp)
        dao.updateMuteEndTime("1@s.whatsapp.net", 999)
        dao.updatePinned("1@s.whatsapp.net", true)
        dao.updateArchived("1@s.whatsapp.net", true)
        dao.updateMarkedAsUnread("1@s.whatsapp.net", true)
        val back = dao.getConversation("1@s.whatsapp.net")!!
        assertEquals(999L, back.muteEndTime)
        assertTrue(back.pinned && back.archived && back.markedAsUnread)
        dao.upsert(WhatsAppConversation(chatJid = "2@g.us", lastMessageTimestamp = 20))
        assertEquals(listOf("2@g.us", "1@s.whatsapp.net"), dao.getRecent(10).map { it.chatJid })
        dao.delete("1@s.whatsapp.net")
        assertNull(dao.getConversation("1@s.whatsapp.net"))
        dao.deleteAll()
        assertEquals(emptyList(), dao.getRecent(10))
    }

    @Test
    fun deviceAndSession_daos() = runTest {
        val devices = db.deviceDao()
        assertNull(devices.getDevice("d1"))
        devices.insertDevice(WhatsAppDevice("d1", "+1555", "Al", "android", 5L))
        assertEquals("+1555", devices.getDevice("d1")!!.phoneNumber)
        devices.deleteDevice("d1")
        assertNull(devices.getDevice("d1"))

        val sessions = db.sessionDao()
        assertFalse(sessions.containsSession("a:0@s.whatsapp.net"))
        sessions.insertSession(WhatsAppSession("a:0@s.whatsapp.net", byteArrayOf(1, 2), 7L))
        assertTrue(sessions.containsSession("a:0@s.whatsapp.net"))
        assertEquals(1, sessions.getAllSessions().size)
        assertNull(sessions.getSession("missing"))
        sessions.deleteSession("a:0@s.whatsapp.net")
        assertFalse(sessions.containsSession("a:0@s.whatsapp.net"))
        sessions.insertSession(WhatsAppSession("x", byteArrayOf(9), 1L))
        sessions.clearAllSessions()
        assertEquals(emptyList(), sessions.getAllSessions())
    }

    @Test
    fun session_equalsAndHashCode() {
        val a = WhatsAppSession("j", byteArrayOf(1, 2), 3L)
        val b = WhatsAppSession("j", byteArrayOf(1, 2), 3L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != WhatsAppSession("other", byteArrayOf(1, 2), 3L))
        assertTrue(a != WhatsAppSession("j", byteArrayOf(9), 3L))
        assertTrue(a != WhatsAppSession("j", byteArrayOf(1, 2), 4L))
        assertFalse(a.equals("x"))
    }

    @Test
    fun mediaRequest_unrequestedThenDelete() = runTest {
        val dao = db.mediaRequestDao()
        dao.upsert(WhatsAppMediaRequest(messageId = "m1", status = 0))
        dao.upsert(WhatsAppMediaRequest(messageId = "m2", status = 1))
        assertEquals(listOf("m1"), dao.getUnrequested().map { it.messageId })
        dao.delete("m1")
        assertEquals(emptyList(), dao.getUnrequested())
    }

    @Test
    fun avatarCache_putAndGet() = runTest {
        val dao = db.avatarCacheDao()
        assertNull(dao.get("e", "a"))
        dao.put(WhatsAppAvatarCache("e", "a", directPath = "/p", expiry = 8))
        assertEquals("/p", dao.get("e", "a")!!.directPath)
        dao.put(WhatsAppAvatarCache("e", "a", directPath = "/p2"))
        assertEquals("/p2", dao.get("e", "a")!!.directPath)
    }

    @Test
    fun pollOptions_crudByMessageAndHash() = runTest {
        val dao = db.pollOptionDao()
        dao.upsertAll(
            listOf(
                WhatsAppPollOption("P1", "h1", "Alpha"),
                WhatsAppPollOption("P1", "h2", "Beta"),
            ),
        )
        assertEquals(2, dao.getByMessageId("P1").size)
        assertEquals("Beta", dao.getByHash("P1", "h2")!!.optionName)
        assertNull(dao.getByHash("P1", "nope"))
        dao.deleteByMessageId("P1")
        assertEquals(emptyList(), dao.getByMessageId("P1"))
    }

    @Test
    fun pollSecret_upsertAndGet() = runTest {
        val dao = db.pollSecretDao()
        assertNull(dao.get("P9"))
        dao.upsert(WhatsAppPollSecret("P9", "s3cr3t"))
        assertEquals("s3cr3t", dao.get("P9"))
    }

    @Test
    fun e2eStores_insertGetDelete() = runTest {
        val sess = db.e2eSessionDao()
        sess.insert(WhatsAppE2ESession("u", 0, byteArrayOf(1)))
        assertTrue(sess.exists("u", 0))
        assertEquals(listOf(0), sess.getSubDeviceIds("u"))
        assertTrue(sess.get("u", 0)!!.record.contentEquals(byteArrayOf(1)))
        sess.delete("u", 0)
        assertFalse(sess.exists("u", 0))
        sess.insert(WhatsAppE2ESession("u", 1, byteArrayOf(2)))
        sess.deleteAll("u")
        assertFalse(sess.exists("u", 1))

        val ident = db.e2eIdentityDao()
        ident.insert(WhatsAppE2EIdentity("u", byteArrayOf(3)))
        assertTrue(ident.get("u")!!.identityKey.contentEquals(byteArrayOf(3)))
        ident.delete("u")
        assertNull(ident.get("u"))

        val pre = db.e2ePreKeyDao()
        assertEquals(0, pre.getCount())
        assertEquals(0, pre.getMaxId())
        pre.insertAll(listOf(WhatsAppE2EPreKey(1, byteArrayOf(1)), WhatsAppE2EPreKey(2, byteArrayOf(2))))
        assertEquals(2, pre.getCount())
        assertEquals(2, pre.getMaxId())
        assertEquals(2, pre.getUnuploaded().size)
        assertTrue(pre.exists(1))
        pre.markUploadedUpTo(1)
        assertEquals(listOf(2), pre.getUnuploaded().map { it.id })
        pre.delete(2)
        assertEquals(1, pre.getAll().size)
        assertNull(pre.get(2))

        val spk = db.e2eSignedPreKeyDao()
        spk.insert(WhatsAppE2ESignedPreKey(7, byteArrayOf(7)))
        assertTrue(spk.exists(7))
        assertEquals(1, spk.getAll().size)
        spk.delete(7)
        assertFalse(spk.exists(7))

        val sender = db.e2eSenderKeyDao()
        assertNull(sender.get("g", 0, "d1"))
        sender.insert(WhatsAppE2ESenderKey("g", 0, "d1", byteArrayOf(8)))
        assertTrue(sender.get("g", 0, "d1")!!.record.contentEquals(byteArrayOf(8)))
        sender.delete("g", 0, "d1")
        assertNull(sender.get("g", 0, "d1"))
    }

    @Test
    fun cachedMessages_lifecycleAndStatusGuards() = runTest {
        val dao = db.cachedMessageDao()
        dao.upsertAll(
            listOf(
                WhatsAppCachedMessage("m1", "c1", "hi", 100, false),
                WhatsAppCachedMessage("m2", "c1", "yo", 200, true),
                WhatsAppCachedMessage("m3", "c2", "hey", 150, false),
            ),
        )
        assertEquals(listOf("m1", "m2"), dao.getForConversation("c1").map { it.messageId })
        assertEquals("yo", dao.get("m2")!!.body)
        // latest per conversation, newest first
        assertEquals(listOf("m2", "m3"), dao.getLatestPerConversation().map { it.messageId })

        dao.markEdited("m1", "hi edited")
        val edited = dao.get("m1")!!
        assertTrue(edited.isEdited)
        assertEquals("hi edited", edited.body)

        dao.markRevoked("m2")
        val revoked = dao.get("m2")!!
        assertTrue(revoked.isRevoked)
        assertEquals("", revoked.body)

        dao.updateServiceData("m3", """{"isRevoked":true}""")
        assertEquals("""{"isRevoked":true}""", dao.get("m3")!!.serviceData)

        // status only advances, never downgrades
        dao.markDelivered("m2")
        assertEquals(2, dao.get("m2")!!.status)
        dao.markReadStatus("m2")
        assertEquals(3, dao.get("m2")!!.status)
        dao.markDelivered("m2")
        assertEquals(3, dao.get("m2")!!.status)

        dao.deleteConversation("c1")
        assertEquals(emptyList(), dao.getForConversation("c1"))
        dao.deleteAll()
        assertEquals(emptyList(), dao.getLatestPerConversation())
    }

    @Test
    fun cachedReactions_upsertRemoveClear() = runTest {
        val dao = db.cachedReactionDao()
        dao.upsert(WhatsAppCachedReaction("m1", "👍", "a@s.whatsapp.net", 5))
        dao.upsert(WhatsAppCachedReaction("m1", "❤️", "b@s.whatsapp.net", 6))
        assertEquals(2, dao.getForMessage("m1").size)
        dao.remove("m1", "a@s.whatsapp.net")
        assertEquals(listOf("❤️"), dao.getForMessage("m1").map { it.emoji })
        dao.clearForMessage("m1")
        assertEquals(emptyList(), dao.getForMessage("m1"))
    }

    @Test
    fun contacts_upsertAndQueries() = runTest {
        val dao = db.contactDao()
        dao.upsertAll(
            listOf(
                WhatsAppContact("+15550001", lid = "l1", displayName = "Zed", onWhatsApp = true, updatedAt = 1),
                WhatsAppContact("+15550002", displayName = "Amy", onWhatsApp = false, updatedAt = 2),
            ),
        )
        assertEquals("Zed", dao.get("+15550001")!!.displayName)
        assertEquals("+15550001", dao.getByLid("l1")!!.phoneE164)
        assertNull(dao.getByLid("missing"))
        assertEquals(listOf("+15550001"), dao.getOnWhatsApp().map { it.phoneE164 })
        assertEquals(2, dao.getAll().size)
        dao.upsert(WhatsAppContact("+15550002", displayName = "Amy!", onWhatsApp = true, updatedAt = 3))
        assertEquals("Amy!", dao.get("+15550002")!!.displayName)
        dao.deleteAll()
        assertEquals(emptyList(), dao.getAll())
    }

    @Test
    fun callLog_recentOrderedByStartDesc() = runTest {
        val dao = db.callLogDao()
        assertNull(dao.get("c1"))
        dao.upsert(WhatsAppCallLog("c1", peerJid = "a", startTime = 10, outcome = "missed"))
        dao.upsert(WhatsAppCallLog("c2", peerJid = "b", startTime = 30, outcome = "answered", video = true))
        dao.upsert(WhatsAppCallLog("c3", peerJid = "c", startTime = 20, outcome = "rejected"))
        assertEquals(listOf("c2", "c3", "c1"), dao.getRecent(10).map { it.callId })
        assertEquals(listOf("c2", "c3"), dao.getRecent(2).map { it.callId })
        assertEquals("missed", dao.get("c1")!!.outcome)
        dao.deleteAll()
        assertEquals(emptyList(), dao.getRecent(10))
    }

    @Test
    fun typeConverters_roundTrip() {
        val c = WhatsAppTypeConverters()
        assertEquals("", c.fromStringList(emptyList()))
        assertEquals(emptyList(), c.toStringList(""))
        assertEquals("a,b,c", c.fromStringList(listOf("a", "b", "c")))
        assertEquals(listOf("a", "b", "c"), c.toStringList("a,b,c"))
    }
}
