package com.vayunmathur.securemail.data

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MailDatabaseTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun db() = Room.inMemoryDatabaseBuilder(ctx, MailDatabase::class.java).allowMainThreadQueries().build()
    private fun msg(id: String, folder: MailFolder = MailFolder.INBOX, date: Long = 0, read: Boolean = false) = StoredMessage(
        id = id, from = "a@x.org", toJson = "[]", dateMillis = date, messageId = id,
        blobBase64 = "AA==", isRead = read, folder = folder,
    )

    @Test fun `message dao upsert folder read trash and delete`() = runBlocking {
        val db = db(); val dao = db.messageDao()
        dao.upsert(msg("1", date = 100)); dao.upsert(msg("2", date = 200)); dao.upsert(msg("3", MailFolder.TRASH, 300))
        assertEquals(listOf("2", "1"), dao.observeFolder(MailFolder.INBOX).first().map { it.id })
        assertEquals(3, dao.observeAll().first().size)
        dao.setRead(listOf("1"), true)
        assertEquals(true, dao.get("1")?.isRead)
        dao.markRead("2"); assertEquals(true, dao.get("2")?.isRead)
        dao.storeDecrypted("1", "body", "subj", "signer", "<b>hi</b>")
        val stored = dao.get("1")!!
        assertEquals("body", stored.decryptedBody); assertEquals("subj", stored.decryptedSubject)
        assertEquals("signer", stored.verifiedSigner); assertEquals("<b>hi</b>", stored.decryptedHtml)
        dao.moveTo(listOf("1"), MailFolder.TRASH)
        assertEquals(MailFolder.TRASH, dao.get("1")?.folder)
        dao.emptyTrash()
        assertNull(dao.get("1")); assertNull(dao.get("3")); assertEquals("2", dao.get("2")?.id)
        dao.deleteAll(listOf("2")); assertEquals(0, dao.observeAll().first().size)
        dao.upsert(msg("9")); dao.delete("9"); assertNull(dao.get("9"))
        assertEquals(4, MailDatabase.migrations.size)
        db.close()
    }

    @Test fun `key outbox and identity daos`() = runBlocking {
        val db = db()
        db.keyDao().upsert(CachedKey("a@x.org", "armor", "FF00", "Wkd", 5))
        assertEquals("armor", db.keyDao().get("a@x.org")?.armoredPublic)
        db.keyDao().upsert(CachedKey("a@x.org", "armor2", "FF01", "Cached", 6))
        assertEquals("armor2", db.keyDao().get("a@x.org")?.armoredPublic)
        assertNull(db.keyDao().get("b@x.org"))

        val first = db.outboxDao().insert(OutboxMessage(toJson = "[]", subject = "s", body = "b", createdMillis = 10))
        val second = db.outboxDao().insert(OutboxMessage(toJson = "[]", subject = "s2", body = "b2", createdMillis = 5))
        assertEquals(2, db.outboxDao().observeCount().first())
        assertEquals(listOf(second, first), db.outboxDao().all().map { it.rowId })
        db.outboxDao().noteAttempt(first, "boom")
        val attempted = db.outboxDao().all().first { it.rowId == first }
        assertEquals("boom", attempted.lastError); assertEquals(1, attempted.attemptCount)
        db.outboxDao().delete(first); assertEquals(1, db.outboxDao().observeCount().first())

        db.identityDao().upsert(MailIdentity("alias@maemail.org", "alias", "pub", "AA", "sec", isPrimary = false, createdMillis = 20))
        db.identityDao().upsert(MailIdentity("me@maemail.org", "primary", "pub", "BB", "sec", isPrimary = true, createdMillis = 10))
        assertEquals(listOf("me@maemail.org", "alias@maemail.org"), db.identityDao().observeAll().first().map { it.address })
        assertEquals("me@maemail.org", db.identityDao().primary()?.address)
        assertEquals(2, db.identityDao().count())
        db.identityDao().setDisabled("me@maemail.org", true) // primary cannot be disabled
        assertEquals(false, db.identityDao().get("me@maemail.org")?.disabled)
        db.identityDao().setDisabled("alias@maemail.org", true)
        assertEquals(listOf("me@maemail.org"), db.identityDao().active().map { it.address })
        db.identityDao().deleteAll(); assertEquals(0, db.identityDao().count())
        assertTrue(MailFolder.entries.contains(MailFolder.SPAM))
        db.close()
    }
}
