package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalCachedMessage
import com.vayunmathur.communicate.data.signal.SignalCallLog
import com.vayunmathur.communicate.data.signal.SignalContact
import com.vayunmathur.communicate.data.signal.SignalConversation
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalTypeConverters
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave 2 (w6 slice 2/6): [SignalDatabase] DAO round trips — conversations, cached messages
 * (incl. the delivery-status ladder), reactions, contacts (ACI/PNI lookups), sender
 * certificates, profile keys, E2E stores and the call log — plus type converters and the
 * migration chain. In-memory Room under Robolectric, mirroring RcsSlice3DatabaseTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalSlice2DatabaseTest {

    private lateinit var db: SignalDatabase

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun conversationCrudAndFlags() = runTest {
        val dao = db.conversationDao()
        assertNull(dao.getConversation("sig-alice"))
        dao.upsert(SignalConversation(chatId = "sig-alice", name = "Alice", unreadCount = 2))
        val got = dao.getConversation("sig-alice") ?: error("missing")
        assertEquals("Alice", got.name)
        assertEquals(2, got.unreadCount)
        // Replace + flags.
        dao.upsert(got.copy(pinned = true, archived = true, isGroup = true, participants = "a,b"))
        val flagged = dao.getConversation("sig-alice")!!
        assertTrue(flagged.pinned && flagged.archived && flagged.isGroup)
        assertEquals("a,b", flagged.participants)
        dao.updatePinned("sig-alice", false)
        assertEquals(false, dao.getConversation("sig-alice")?.pinned)
        dao.updateArchived("sig-alice", false)
        assertEquals(false, dao.getConversation("sig-alice")?.archived)
        // Recent ordering + delete.
        dao.upsert(SignalConversation(chatId = "sig-bob", lastMessageTimestamp = 999L))
        assertEquals("sig-bob", dao.getRecent(10).first().chatId)
        dao.delete("sig-alice")
        assertNull(dao.getConversation("sig-alice"))
        dao.deleteAll()
        assertTrue(dao.getRecent(10).isEmpty())
    }

    @Test
    fun groupConversationKeepsMasterKeyAndRevision() = runTest {
        val dao = db.conversationDao()
        val masterKey = ByteArray(32) { 7 }
        dao.upsert(
            SignalConversation(
                chatId = "group:abc", isGroup = true, name = "Trip",
                participants = "alice,bob", groupMasterKey = masterKey, groupRevision = 3,
            ),
        )
        val got = dao.getConversation("group:abc")!!
        assertTrue(got.groupMasterKey!!.contentEquals(masterKey))
        assertEquals(3, got.groupRevision)
        assertEquals("alice,bob", got.participants)
    }

    @Test
    fun cachedMessageCrudAndStatusLadder() = runTest {
        val dao = db.cachedMessageDao()
        dao.upsert(
            SignalCachedMessage(
                messageId = "s2-m1", conversationId = "s2-conv", body = "hello",
                timestamp = 100L, outgoing = true, senderId = "me",
            ),
        )
        assertEquals("hello", dao.get("s2-m1")?.body)
        // Status only advances upward.
        dao.upsert(dao.get("s2-m1")!!.copy(status = 1))
        dao.markDelivered("s2-m1")
        assertEquals(2, dao.get("s2-m1")?.status)
        dao.markDelivered("s2-m1")
        assertEquals(2, dao.get("s2-m1")?.status)
        dao.markReadStatus("s2-m1")
        assertEquals(3, dao.get("s2-m1")?.status)
        dao.markReadStatus("s2-m1")
        assertEquals(3, dao.get("s2-m1")?.status)
        // Second message for ordering + lookups.
        dao.upsert(
            SignalCachedMessage(
                messageId = "s2-m2", conversationId = "s2-conv", body = "second",
                timestamp = 200L, outgoing = true,
            ),
        )
        assertEquals(listOf("s2-m1", "s2-m2"), dao.getForConversation("s2-conv").map { it.messageId })
        assertEquals("s2-m2", dao.getLatestPerConversation().first().messageId)
        // Timestamp lookups: outgoing-only vs either direction.
        assertEquals("s2-m2", dao.getOutgoingByTimestamp(200L)?.messageId)
        dao.upsert(
            SignalCachedMessage(
                messageId = "s2-m3", conversationId = "s2-conv", body = "in",
                timestamp = 300L, outgoing = false,
            ),
        )
        assertNull(dao.getOutgoingByTimestamp(300L))
        assertEquals("s2-m3", dao.getByTimestamp(300L)?.messageId)
        // Edit / revoke / service-data.
        dao.markEdited("s2-m1", "hello v2")
        assertEquals("hello v2", dao.get("s2-m1")?.body)
        assertEquals(true, dao.get("s2-m1")?.isEdited)
        dao.markRevoked("s2-m2")
        assertEquals("", dao.get("s2-m2")?.body)
        assertEquals(true, dao.get("s2-m2")?.isRevoked)
        dao.updateServiceData("s2-m1", """{"isEdited":true}""")
        assertEquals("""{"isEdited":true}""", dao.get("s2-m1")?.serviceData)
        // Delete by conversation + all.
        dao.deleteConversation("s2-conv")
        assertTrue(dao.getForConversation("s2-conv").isEmpty())
        dao.upsert(
            SignalCachedMessage(
                messageId = "s2-m4", conversationId = "s2-other", body = "x",
                timestamp = 1L, outgoing = true,
            ),
        )
        dao.deleteAll()
        assertNull(dao.get("s2-m4"))
    }

    @Test
    fun reactionsUpsertAndRemove() = runTest {
        val dao = db.cachedReactionDao()
        assertTrue(dao.getForMessage("s2-m1").isEmpty())
        dao.upsert(
            com.vayunmathur.communicate.data.signal.SignalCachedReaction("s2-m1", "👍", "alice", 10L),
        )
        dao.upsert(
            com.vayunmathur.communicate.data.signal.SignalCachedReaction("s2-m1", "❤", "bob", 11L),
        )
        assertEquals(2, dao.getForMessage("s2-m1").size)
        // Same sender re-reacts: replace.
        dao.upsert(
            com.vayunmathur.communicate.data.signal.SignalCachedReaction("s2-m1", "😂", "alice", 12L),
        )
        assertEquals(2, dao.getForMessage("s2-m1").size)
        assertTrue(dao.getForMessage("s2-m1").map { it.emoji }.contains("😂"))
        dao.remove("s2-m1", "alice")
        assertEquals(listOf("bob"), dao.getForMessage("s2-m1").map { it.senderId })
        dao.clearForMessage("s2-m1")
        assertTrue(dao.getForMessage("s2-m1").isEmpty())
    }

    @Test
    fun contactAciPniAndPhoneLookups() = runTest {
        val dao = db.contactDao()
        dao.upsert(
            SignalContact(
                aci = "aci-alice", phoneE164 = "+14155552671", displayName = "Alice",
                onSignal = true, pni = "PNI:pni-alice",
            ),
        )
        assertEquals("Alice", dao.get("aci-alice")?.displayName)
        assertEquals("aci-alice", dao.getByPhone("+14155552671")?.aci)
        assertEquals("aci-alice", dao.getByPni("PNI:pni-alice")?.aci)
        assertNull(dao.getByPni("PNI:unknown"))
        // Empty PNI never matches.
        dao.upsert(SignalContact(aci = "aci-bob", phoneE164 = "+14155550000"))
        assertNull(dao.getByPni(""))
        assertEquals(1, dao.getOnSignal().size)
        assertEquals(2, dao.getAll().size)
        dao.deleteByPhone("+14155552671")
        assertNull(dao.get("aci-alice"))
        dao.deleteAll()
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun sealedSenderRows() = runTest {
        val certDao = db.senderCertificateDao()
        assertNull(certDao.get())
        certDao.upsert(
            com.vayunmathur.communicate.data.signal.SignalSenderCertificate(
                record = byteArrayOf(1, 2, 3), expiration = 999L, includesE164 = false,
            ),
        )
        val cert = certDao.get()!!
        assertTrue(cert.record.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(999L, cert.expiration)
        certDao.clear()
        assertNull(certDao.get())

        val pkDao = db.profileKeyDao()
        assertNull(pkDao.get("aci-x"))
        pkDao.upsert(
            com.vayunmathur.communicate.data.signal.SignalProfileKey("aci-x", ByteArray(32) { 4 }),
        )
        assertTrue(pkDao.get("aci-x")!!.profileKey.contentEquals(ByteArray(32) { 4 }))
    }

    @Test
    fun e2eStoresRoundTrip() = runTest {
        // Sessions.
        val sessDao = db.e2eSessionDao()
        assertEquals(false, sessDao.exists("alice", 1))
        sessDao.insert(
            com.vayunmathur.communicate.data.signal.SignalE2ESession("alice", 1, byteArrayOf(9)),
        )
        assertEquals(true, sessDao.exists("alice", 1))
        assertEquals(listOf(1), sessDao.getSubDeviceIds("alice").filter { it == 1 })
        sessDao.delete("alice", 1)
        assertEquals(false, sessDao.exists("alice", 1))

        // Identities.
        val idDao = db.e2eIdentityDao()
        assertNull(idDao.get("alice"))
        idDao.insert(
            com.vayunmathur.communicate.data.signal.SignalE2EIdentity("alice", byteArrayOf(5)),
        )
        assertTrue(idDao.get("alice")!!.identityKey.contentEquals(byteArrayOf(5)))
        idDao.delete("alice")
        assertNull(idDao.get("alice"))

        // One-time pre-keys.
        val preDao = db.e2ePreKeyDao()
        assertEquals(0, preDao.getCount())
        preDao.insert(
            com.vayunmathur.communicate.data.signal.SignalE2EPreKey(21, byteArrayOf(1), uploaded = false),
        )
        assertEquals(1, preDao.getUnuploaded().size)
        assertEquals(21, preDao.getMaxId())
        assertEquals(true, preDao.exists(21))
        preDao.markUploadedUpTo(21)
        assertTrue(preDao.getUnuploaded().isEmpty())
        preDao.insertAll(
            listOf(com.vayunmathur.communicate.data.signal.SignalE2EPreKey(22, byteArrayOf(2))),
        )
        assertEquals(2, preDao.getCount())
        preDao.delete(21)
        assertEquals(false, preDao.exists(21))

        // Signed pre-keys.
        val spkDao = db.e2eSignedPreKeyDao()
        assertEquals(false, spkDao.exists(11))
        spkDao.insert(
            com.vayunmathur.communicate.data.signal.SignalE2ESignedPreKey(11, byteArrayOf(3)),
        )
        assertEquals(true, spkDao.exists(11))
        assertEquals(1, spkDao.getAll().size)
        spkDao.delete(11)
        assertEquals(false, spkDao.exists(11))

        // Kyber pre-keys + used base keys.
        val kyDao = db.e2eKyberPreKeyDao()
        assertNull(kyDao.get(31))
        kyDao.insert(
            com.vayunmathur.communicate.data.signal.SignalE2EKyberPreKey(31, byteArrayOf(4), lastResort = true),
        )
        assertEquals(true, kyDao.exists(31))
        assertEquals(true, kyDao.getAll().first().lastResort)
        val usedDao = db.e2eKyberUsedBaseKeyDao()
        assertEquals(false, usedDao.exists(31, 11, "YmFzZQ=="))
        usedDao.insert(
            com.vayunmathur.communicate.data.signal.SignalE2EKyberUsedBaseKey(31, 11, "YmFzZQ=="),
        )
        assertEquals(true, usedDao.exists(31, 11, "YmFzZQ=="))
        usedDao.deleteForKyberPreKey(31)
        assertEquals(false, usedDao.exists(31, 11, "YmFzZQ=="))
        kyDao.delete(31)
        assertEquals(false, kyDao.exists(31))

        // Sender keys.
        val skDao = db.e2eSenderKeyDao()
        assertNull(skDao.get("alice", 1, "dist-1"))
        skDao.insert(
            com.vayunmathur.communicate.data.signal.SignalE2ESenderKey("alice", 1, "dist-1", byteArrayOf(6)),
        )
        assertTrue(skDao.get("alice", 1, "dist-1")!!.record.contentEquals(byteArrayOf(6)))
        skDao.delete("alice", 1, "dist-1")
        assertNull(skDao.get("alice", 1, "dist-1"))
    }

    @Test
    fun deviceAndSessionTransportRows() = runTest {
        val devDao = db.deviceDao()
        assertNull(devDao.getDevice("d1"))
        devDao.insertDevice(
            com.vayunmathur.communicate.data.signal.SignalDevice("d1", "+1", "Alice", "android", 5L),
        )
        assertEquals("+1", devDao.getDevice("d1")?.phoneNumber)
        devDao.deleteDevice("d1")
        assertNull(devDao.getDevice("d1"))

        val sessDao = db.sessionDao()
        assertNull(sessDao.getSession("alice.1"))
        assertEquals(false, sessDao.containsSession("alice.1"))
        sessDao.insertSession(
            com.vayunmathur.communicate.data.signal.SignalSession("alice.1", byteArrayOf(7), 8L),
        )
        assertEquals(true, sessDao.containsSession("alice.1"))
        assertEquals(1, sessDao.getAllSessions().size)
        sessDao.deleteSession("alice.1")
        assertEquals(false, sessDao.containsSession("alice.1"))
        sessDao.insertSession(
            com.vayunmathur.communicate.data.signal.SignalSession("alice.1", byteArrayOf(7), 8L),
        )
        sessDao.clearAllSessions()
        assertTrue(sessDao.getAllSessions().isEmpty())
    }

    @Test
    fun callLogOrdersByStartTimeDesc() = runTest {
        val dao = db.callLogDao()
        assertNull(dao.get("c1"))
        dao.upsert(SignalCallLog(callId = "c1", peerId = "alice", outgoing = true, startTime = 100L))
        dao.upsert(SignalCallLog(callId = "c2", peerId = "bob", outgoing = false, startTime = 200L))
        assertEquals("alice", dao.get("c1")?.peerId)
        assertEquals(listOf("c2", "c1"), dao.getRecent(10).map { it.callId })
        assertEquals(1, dao.getRecent(1).size)
        // Replace semantics.
        dao.upsert(SignalCallLog(callId = "c1", peerId = "alice", durationSeconds = 60L, outcome = "ended"))
        assertEquals(60L, dao.get("c1")?.durationSeconds)
        dao.deleteAll()
        assertTrue(dao.getRecent(10).isEmpty())
    }

    @Test
    fun typeConvertersAndMigrations() {
        val conv = SignalTypeConverters()
        assertEquals("", conv.fromStringList(emptyList()))
        assertEquals("a,b,c", conv.fromStringList(listOf("a", "b", "c")))
        assertEquals(emptyList(), conv.toStringList(""))
        assertEquals(listOf("a", "b"), conv.toStringList("a,b"))
        val versions = SignalDatabase.migrations.map { it.startVersion to it.endVersion }
        assertTrue(versions.contains(1 to 2))
        assertTrue(versions.contains(2 to 3))
        assertTrue(versions.contains(3 to 4))
        assertTrue(versions.contains(4 to 5))
    }
}
