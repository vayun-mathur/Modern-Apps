package com.vayunmathur.communicate.rcs

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsCachedMessage
import com.vayunmathur.communicate.data.rcs.RcsConversation
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsMlsGroup
import com.vayunmathur.communicate.data.rcs.RcsMlsIdentity
import com.vayunmathur.communicate.data.rcs.RcsOutboxMessage
import com.vayunmathur.communicate.data.rcs.RcsTypeConverters
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
 * Slice-3 tests for the RCS Room database ([RcsDatabase]): DAO round trips
 * for conversations, cached messages (incl. delivery-status advancement),
 * MLS identity/group rows, outbox queueing, and the type converters.
 *
 * Runs against a real in-memory Room database (bundled sqlite driver) under
 * Robolectric, mirroring the brief's
 * `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries().build()`
 * recipe for the Room 3 API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RcsSlice3DatabaseTest {

    private lateinit var db: RcsDatabase

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, RcsDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun conversationRoundTrip() = runTest {
        assertNull(db.conversationDao().getConversation("+15550001111"))
        db.conversationDao().upsert(RcsConversation(remoteId = "+15550001111", unreadCount = 2))
        val got = db.conversationDao().getConversation("+15550001111") ?: error("missing")
        assertEquals(2, got.unreadCount)
        assertEquals("+15550001111", got.remoteId)
        // Replace semantics.
        db.conversationDao().upsert(RcsConversation(remoteId = "+15550001111", unreadCount = 0, name = "Bob"))
        assertEquals("Bob", db.conversationDao().getConversation("+15550001111")?.name)
        // Recent ordering + delete.
        db.conversationDao().upsert(
            RcsConversation(remoteId = "+15550002222", lastMessageTimestamp = 999L),
        )
        val recent = db.conversationDao().getRecent(10)
        assertEquals("+15550002222", recent.first().remoteId)
        db.conversationDao().delete("+15550001111")
        assertNull(db.conversationDao().getConversation("+15550001111"))
    }

    @Test
    fun cachedMessageCrudAndStatusLadder() = runTest {
        val dao = db.cachedMessageDao()
        dao.upsert(
            RcsCachedMessage(
                messageId = "slice3-m1",
                conversationId = "slice3-conv",
                body = "hello",
                timestamp = 100L,
                outgoing = true,
                imdnId = "imdn-1",
            ),
        )
        assertEquals("hello", dao.get("slice3-m1")?.body)
        assertEquals("imdn-1", dao.get("slice3-m1")?.imdnId)
        // Status only advances upward.
        dao.upsert(dao.get("slice3-m1")!!.copy(status = 1))
        dao.markDelivered("slice3-m1")
        assertEquals(2, dao.get("slice3-m1")?.status)
        dao.markDelivered("slice3-m1")
        assertEquals(2, dao.get("slice3-m1")?.status)
        dao.markReadStatus("slice3-m1")
        assertEquals(3, dao.get("slice3-m1")?.status)
        // A fresh row at status 0 jumps straight to read.
        dao.upsert(
            RcsCachedMessage(
                messageId = "slice3-m2",
                conversationId = "slice3-conv",
                body = "second",
                timestamp = 200L,
                outgoing = true,
            ),
        )
        dao.markReadStatus("slice3-m2")
        assertEquals(3, dao.get("slice3-m2")?.status)
        // Per-conversation listing is timestamp-ordered.
        val listed = dao.getForConversation("slice3-conv")
        assertEquals(listOf("slice3-m1", "slice3-m2"), listed.map { it.messageId })
        // Latest-per-conversation query.
        val latest = dao.getLatestPerConversation()
        assertEquals("slice3-m2", latest.first().messageId)
        // Delete by conversation.
        dao.deleteConversation("slice3-conv")
        assertTrue(dao.getForConversation("slice3-conv").isEmpty())
    }

    @Test
    fun mlsIdentityAndGroupRoundTrip() = runTest {
        val idDao = db.mlsIdentityDao()
        assertNull(idDao.get("+15550003333"))
        idDao.upsert(RcsMlsIdentity(e164 = "+15550003333", identity = byteArrayOf(1, 2, 3)))
        val got = idDao.get("+15550003333") ?: error("missing identity")
        assertTrue(got.identity.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(false, got.verified)
        idDao.upsert(got.copy(verified = true))
        assertEquals(true, idDao.get("+15550003333")?.verified)
        idDao.delete("+15550003333")
        assertNull(idDao.get("+15550003333"))

        val groupDao = db.mlsGroupDao()
        assertNull(groupDao.getByConversation("slice3-gconv"))
        groupDao.upsert(
            RcsMlsGroup(
                groupIdHex = "aabbcc",
                conversationId = "slice3-gconv",
                storage = byteArrayOf(9, 9),
                members = "+1=1,+2=2",
            ),
        )
        assertEquals("aabbcc", groupDao.get("aabbcc")?.groupIdHex)
        assertEquals("+1=1,+2=2", groupDao.getByConversation("slice3-gconv")?.members)
        groupDao.deleteByConversation("slice3-gconv")
        assertNull(groupDao.get("aabbcc"))
    }

    @Test
    fun outboxQueueLifecycle() = runTest {
        val dao = db.outboxDao()
        assertEquals(0, dao.count())
        val now = System.currentTimeMillis()
        dao.upsert(
            RcsOutboxMessage(
                messageId = "slice3-ob-1",
                conversationId = "slice3-conv",
                kind = "text",
                body = "retry me",
                nextRetryMs = now - 1000,
                expiryMs = now + 86_400_000L,
            ),
        )
        dao.upsert(
            RcsOutboxMessage(
                messageId = "slice3-ob-2",
                conversationId = "slice3-conv",
                kind = "file",
                body = "",
                attachments = "content://x/1",
                nextRetryMs = now + 86_400_000L,
                expiryMs = now + 2 * 86_400_000L,
            ),
        )
        assertEquals(2, dao.count())
        // Only the due row is returned.
        val due = dao.dueForRetry(now)
        assertEquals(listOf("slice3-ob-1"), due.map { it.messageId })
        assertEquals("slice3-ob-1", dao.get("slice3-ob-1")?.body?.let { "slice3-ob-1" })
        dao.delete("slice3-ob-1")
        assertEquals(1, dao.count())
        dao.deleteConversation("slice3-conv")
        assertEquals(0, dao.count())
    }

    @Test
    fun typeConvertersRoundTrip() {
        val conv = RcsTypeConverters()
        assertEquals("", conv.fromStringList(emptyList()))
        assertEquals("a,b,c", conv.fromStringList(listOf("a", "b", "c")))
        assertEquals(emptyList(), conv.toStringList(""))
        assertEquals(listOf("a", "b"), conv.toStringList("a,b"))
    }

    @Test
    fun migrationsListCoversV1ToV3() {
        val versions = RcsDatabase.migrations.map { it.startVersion to it.endVersion }
        assertTrue(versions.contains(1 to 2))
        assertTrue(versions.contains(2 to 3))
    }
}
