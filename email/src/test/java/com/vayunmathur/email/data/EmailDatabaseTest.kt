package com.vayunmathur.email.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the email Room DAOs (and the thin store wrappers around them) against
 * an in-memory database.
 *
 * These queries encode real product behavior — snoozed mail hiding until it
 * resurfaces, tombstones stopping deleted mail from syncing back, list
 * previews reading the stored snippet instead of the full body — so each
 * test asserts the persisted rows, not just that the call succeeded.
 */
@RunWith(AndroidJUnit4::class)
class EmailDatabaseTest {

    private lateinit var db: EmailDatabase

    private fun message(
        uid: Long,
        subject: String = "subject",
        from: String = "a@b.com",
        body: String? = "body text",
        dateMillis: Long = 1000L + uid,
        folder: String = "INBOX",
        account: String = "me@x.com",
        snoozedUntil: Long = 0L,
        isRead: Boolean = false,
    ) = EmailMessage(
        accountEmail = account,
        folderName = folder,
        id = uid,
        subject = subject,
        from = from,
        date = "Wed, 17 Jul 2024 12:00:00 +0000",
        dateMillis = dateMillis,
        body = body,
        snoozedUntil = snoozedUntil,
        isRead = isRead,
    )

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EmailDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @AfterTest
    fun close() {
        db.close()
    }

    /**
     * A Room Flow collection started right after a write can still deliver
     * the pre-write snapshot as its first emission (the invalidation
     * re-query races the initial query on Room's query executor), so a
     * single `first()` after a delete is flaky under load. Re-collect until
     * the flow reflects the write, then return that emission for asserting.
     */
    private suspend fun <T> Flow<List<T>>.awaitList(predicate: (List<T>) -> Boolean): List<T> {
        repeat(600) {
            val value = first()
            if (predicate(value)) return value
            Thread.sleep(10)
        }
        return first()
    }

    // ---------- accounts / folders / blocked senders ----------

    @Test
    fun accounts_insertGetUpdateSignatureAndDelete() = runTest {
        val dao = db.accountDao()
        assertNull(dao.getAccountByEmail("me@x.com"))
        dao.insertAccount(EmailAccount(email = "me@x.com"))
        assertEquals("me@x.com", dao.getAccountByEmail("me@x.com")!!.email)
        assertEquals(1, dao.getAccounts().size)
        assertEquals(1, dao.getAccountsFlow().first().size)
        dao.setSignature("me@x.com", "Sent from my phone")
        assertEquals("Sent from my phone", dao.getAccountByEmail("me@x.com")!!.signature)
        dao.deleteAccount(EmailAccount(email = "me@x.com"))
        assertNull(dao.getAccountByEmail("me@x.com"))
        assertEquals(emptyList(), dao.getAccountsFlow().awaitList { it.isEmpty() })
    }

    @Test
    fun folders_insertClearAndFlow() = runTest {
        val dao = db.accountDao()
        dao.insertFolders(
            listOf(
                EmailFolder("me@x.com", "INBOX", "INBOX"),
                EmailFolder("me@x.com", "INBOX/Work", "Work", parentFullName = "INBOX"),
            )
        )
        val folders = dao.getFoldersFlow("me@x.com").first()
        assertEquals(2, folders.size)
        assertTrue(folders.any { it.fullName == "INBOX/Work" && it.parentFullName == "INBOX" })
        assertEquals(emptyList(), dao.getFoldersFlow("other@x.com").first())
        dao.clearFolders("me@x.com")
        assertEquals(emptyList(), dao.getFoldersFlow("me@x.com").awaitList { it.isEmpty() })
    }

    @Test
    fun blockedSenders_insertDeleteAndFlow() = runTest {
        val dao = db.accountDao()
        dao.insertBlockedSender(BlockedSender("spam@evil.com"))
        dao.insertBlockedSender(BlockedSender("ads@evil.com"))
        assertEquals(
            listOf("ads@evil.com", "spam@evil.com"),
            dao.getBlockedSendersFlow().first().map { it.address },
        )
        dao.deleteBlockedSender("ads@evil.com")
        assertEquals(
            listOf("spam@evil.com"),
            dao.getBlockedSendersFlow().awaitList { it.size == 1 }.map { it.address },
        )
    }

    @Test
    fun accountStore_delegatesToAccountDao() = runTest {
        val store = EmailAccountStore(db)
        store.insertAccount(EmailAccount(email = "me@x.com"))
        assertEquals(1, store.getAccounts().size)
        assertEquals(1, store.getAccountsFlow().first().size)
        assertEquals("me@x.com", store.getAccountByEmail("me@x.com")!!.email)
        store.setSignature("me@x.com", "sig")
        assertEquals("sig", store.getAccountByEmail("me@x.com")!!.signature)
        store.insertBlockedSender(BlockedSender("s@pam.com"))
        assertEquals(1, store.getBlockedSendersFlow().first().size)
        store.deleteBlockedSender("s@pam.com")
        assertEquals(emptyList(), store.getBlockedSendersFlow().awaitList { it.isEmpty() })
        store.insertFolders(listOf(EmailFolder("me@x.com", "INBOX", "INBOX")))
        assertEquals(1, store.getFoldersFlow("me@x.com").first().size)
        store.clearFolders("me@x.com")
        assertEquals(emptyList(), store.getFoldersFlow("me@x.com").awaitList { it.isEmpty() })
        store.deleteAccount(EmailAccount(email = "me@x.com"))
        assertNull(store.getAccountByEmail("me@x.com"))
    }

    // ---------- messages ----------

    @Test
    fun insertMessages_computesPeekSnippetFromBody() = runTest {
        val dao = db.messageDao()
        dao.insertMessages(listOf(message(1L, body = "<p>Hello world</p>")))
        val back = dao.getMessage("me@x.com", "INBOX", 1L)!!
        assertTrue(back.peekContent.contains("Hello world"), back.peekContent)
        assertNull(dao.getMessage("me@x.com", "INBOX", 999L))
    }

    @Test
    fun messagesFlow_hidesSnoozedUntilDue() = runTest {
        val dao = db.messageDao()
        dao.insertMessages(
            listOf(
                message(1L, snoozedUntil = 0L),
                message(2L, snoozedUntil = 9_999_999_999_999L),
            )
        )
        val visible = dao.getMessagesFlow("me@x.com", "INBOX", now = 1000L).first()
        assertEquals(listOf(1L), visible.map { it.id })
    }

    @Test
    fun deleteWithTombstone_recordsUidAndRemovesRow() = runTest {
        val dao = db.messageDao()
        dao.insertMessages(listOf(message(1L), message(2L)))
        assertEquals(listOf(1L, 2L), dao.getKnownUids("me@x.com", "INBOX").sorted())
        dao.deleteMessageRow("me@x.com", "INBOX", 1L, tombstone = true)
        assertNull(dao.getMessage("me@x.com", "INBOX", 1L))
        assertEquals(listOf(1L), dao.getDeletedUids("me@x.com", "INBOX"))
        dao.deleteMessageRow("me@x.com", "INBOX", 2L, tombstone = false)
        assertEquals(listOf(1L), dao.getDeletedUids("me@x.com", "INBOX"))
        // Plain overload removes the row without touching tombstones.
        dao.insertMessages(listOf(message(3L)))
        dao.deleteMessageRow("me@x.com", "INBOX", 3L)
        assertNull(dao.getMessage("me@x.com", "INBOX", 3L))
        assertEquals(listOf(1L), dao.getDeletedUids("me@x.com", "INBOX"))
    }

    @Test
    fun messagesWithoutBody_returnsOnlyHeaderOnlyRowsNewestFirst() = runTest {
        val dao = db.messageDao()
        dao.insertMessages(listOf(message(1L, body = null), message(2L, body = "has body"), message(3L, body = null)))
        assertEquals(listOf(3L, 1L), dao.getMessagesWithoutBody("me@x.com", 10).map { it.id })
        assertEquals(listOf(3L), dao.getMessagesWithoutBody("me@x.com", 1).map { it.id })
    }

    @Test
    fun clearMessages_removesOnlyThatAccount() = runTest {
        val dao = db.messageDao()
        dao.insertMessages(listOf(message(1L, account = "me@x.com"), message(2L, account = "you@y.com")))
        dao.clearMessages("me@x.com")
        assertNull(dao.getMessage("me@x.com", "INBOX", 1L))
        assertEquals(2L, dao.getMessage("you@y.com", "INBOX", 2L)!!.id)
    }

    @Test
    fun snoozeAndWake_resurfacesDueMailAsUnread() = runTest {
        val dao = db.messageDao()
        dao.insertMessages(listOf(message(1L, isRead = true)))
        dao.setSnooze("me@x.com", "INBOX", 1L, 5000L)
        assertEquals(5000L, dao.getMessage("me@x.com", "INBOX", 1L)!!.snoozedUntil)
        assertEquals(0, dao.wakeDueSnoozed(now = 4999L))
        assertEquals(1, dao.wakeDueSnoozed(now = 5000L))
        val back = dao.getMessage("me@x.com", "INBOX", 1L)!!
        assertEquals(0L, back.snoozedUntil)
        assertEquals(false, back.isRead)
    }

    @Test
    fun readStatus_singleAndBulk() = runTest {
        val dao = db.messageDao()
        dao.insertMessages(listOf(message(1L), message(2L), message(3L)))
        dao.updateReadStatus("me@x.com", "INBOX", 1L, true)
        assertEquals(true, dao.getMessage("me@x.com", "INBOX", 1L)!!.isRead)
        dao.updateBulkReadStatus("me@x.com", listOf(2L, 3L), true)
        assertEquals(true, dao.getMessage("me@x.com", "INBOX", 2L)!!.isRead)
        assertEquals(true, dao.getMessage("me@x.com", "INBOX", 3L)!!.isRead)
    }

    @Test
    fun attachments_insertGetAndMarkDownloaded() = runTest {
        val dao = db.messageDao()
        val att = Attachment("me@x.com", "INBOX", 7L, "0", "a.pdf", "application/pdf", 100L)
        dao.insertAttachments(listOf(att))
        assertEquals(listOf("a.pdf"), dao.getAttachments("me@x.com", 7L).map { it.fileName })
        assertEquals(emptyList(), dao.getAttachments("me@x.com", 8L))
        dao.updateAttachmentLocalUri("me@x.com", 7L, "0", "file:///cache/a.pdf")
        assertEquals("file:///cache/a.pdf", dao.getAttachments("me@x.com", 7L).single().localUri)
    }

    @Test
    fun threadFlow_returnsThreadOldestFirst() = runTest {
        val dao = db.messageDao()
        val a = message(1L, dateMillis = 100L).copy(threadId = "t1")
        val b = message(2L, dateMillis = 200L).copy(threadId = "t1")
        val other = message(3L, dateMillis = 50L).copy(threadId = "t2")
        dao.insertMessages(listOf(b, other, a))
        assertEquals(listOf(1L, 2L), dao.getThreadFlow("me@x.com", "t1").first().map { it.id })
    }

    @Test
    fun messageStore_delegatesToMessageDao() = runTest {
        val store = EmailMessageStore(db)
        store.insertMessages(listOf(message(1L, body = "hello snippet")))
        assertEquals("hello snippet", store.getMessage("me@x.com", "INBOX", 1L)!!.peekContent)
        assertEquals(listOf(1L), store.getMessagesFlow("me@x.com", "INBOX", 0L).first().map { it.id })
        // Thread flow for an unknown thread is empty; insert a threaded row and re-check.
        assertEquals(emptyList(), store.getThreadFlow("me@x.com", "t9").first())
        store.insertMessages(listOf(message(2L).copy(threadId = "t9")))
        assertEquals(listOf(2L), store.getThreadFlow("me@x.com", "t9").first().map { it.id })
        store.updateReadStatus("me@x.com", "INBOX", 1L, true)
        assertEquals(true, store.getMessage("me@x.com", "INBOX", 1L)!!.isRead)
        store.updateBulkReadStatus("me@x.com", listOf(1L), false)
        assertEquals(false, store.getMessage("me@x.com", "INBOX", 1L)!!.isRead)
        store.setSnooze("me@x.com", "INBOX", 1L, 9000L)
        assertEquals(9000L, store.getMessage("me@x.com", "INBOX", 1L)!!.snoozedUntil)
        assertEquals(1, store.wakeDueSnoozed(9000L))
        // Both rows 1 and 2 are still present (row 2 was added for the thread check).
        assertEquals(listOf(1L, 2L), store.getKnownUids("me@x.com", "INBOX").sorted())
        store.insertDeletedUid(DeletedUid("me@x.com", "INBOX", 5L))
        assertEquals(listOf(5L), store.getDeletedUids("me@x.com", "INBOX"))
        store.insertAttachments(
            listOf(Attachment("me@x.com", "INBOX", 1L, "p1", "f.txt", "text/plain", 3L))
        )
        assertEquals(1, store.getAttachments("me@x.com", 1L).size)
        store.updateAttachmentLocalUri("me@x.com", 1L, "p1", "uri")
        assertEquals("uri", store.getAttachments("me@x.com", 1L).single().localUri)
        assertEquals(emptyList(), store.getMessagesWithoutBody("me@x.com", 5))
        store.deleteMessageRow("me@x.com", "INBOX", 1L)
        assertNull(store.getMessage("me@x.com", "INBOX", 1L))
        store.insertMessages(listOf(message(9L)))
        store.deleteMessageRow("me@x.com", "INBOX", 9L, tombstone = true)
        assertEquals(listOf(5L, 9L), store.getDeletedUids("me@x.com", "INBOX").sorted())
        store.clearMessages("me@x.com")
        assertEquals(emptyList(), store.getKnownUids("me@x.com", "INBOX"))
    }

    // ---------- search / unified / previews / backfills ----------

    @Test
    fun searchAndUnified_matchSubjectSenderAndBody() = runTest {
        val dao = db.queryDao()
        db.messageDao().insertMessagesRaw(
            listOf(
                message(1L, subject = "Quarterly report", from = "boss@work.com", body = "numbers"),
                message(2L, subject = "Hello", from = "friend@x.com", body = "dinner plans"),
            )
        )
        assertEquals(listOf(1L), dao.searchMessages("quarterly").map { it.id })
        assertEquals(listOf(2L), dao.searchMessages("FRIEND").map { it.id })
        assertEquals(listOf(2L), dao.searchMessages("dinner").map { it.id })
        assertEquals(emptyList(), dao.searchMessages("zzz-no-match"))
        assertEquals(
            listOf(1L),
            dao.searchMessagesFlow("me@x.com", "INBOX", "report", 0L).first().map { it.id },
        )
        assertEquals(
            listOf(1L, 2L),
            dao.getUnifiedMessagesFlow("INBOX", 0L).first().map { it.id }.sorted(),
        )
        assertEquals(
            listOf(2L),
            dao.searchUnifiedMessagesFlow("INBOX", "dinner", 0L).first().map { it.id },
        )
    }

    @Test
    fun recentInbox_capsAtThirtyNewestFirst() = runTest {
        val dao = db.queryDao()
        db.messageDao().insertMessagesRaw((1L..35L).map { message(it, dateMillis = it) })
        val recent = dao.getRecentInboxMessages()
        assertEquals(30, recent.size)
        assertEquals(35L, recent.first().id)
        assertEquals(6L, recent.last().id)
        // The unified variants cap wider (100 rows / 10 for the flow).
        assertEquals(35, dao.getRecentUnifiedMessages().size)
        assertEquals(10, dao.getRecentUnifiedMessagesFlow().first().size)
    }

    @Test
    fun previewFlows_carryStoredSnippetWithoutBody() = runTest {
        val dao = db.queryDao()
        db.messageDao().insertMessages(listOf(message(1L, body = "preview me please")))
        val previews = dao.getMessagesPreviewFlow("me@x.com", "INBOX", 0L).first()
        assertEquals(1, previews.size)
        assertEquals("preview me please", previews.single().peekContent)
        assertEquals(
            1,
            dao.searchMessagesPreviewFlow("me@x.com", "INBOX", "preview", 0L).first().size,
        )
        assertEquals(
            1,
            dao.getUnifiedMessagesPreviewFlow("INBOX", 0L).first().size,
        )
        assertEquals(
            1,
            dao.searchUnifiedMessagesPreviewFlow("INBOX", "preview", 0L).first().size,
        )
        assertEquals(1, dao.getRecentUnifiedPreview().size)
    }

    @Test
    fun dateMillisBackfill_findsZeroRowsAndUpdates() = runTest {
        val dao = db.queryDao()
        db.messageDao().insertMessagesRaw(listOf(message(1L).copy(dateMillis = 0L), message(2L).copy(dateMillis = 5L)))
        assertEquals(listOf(1L), dao.getRowsWithZeroDateMillis().map { it.id })
        dao.updateDateMillis("me@x.com", "INBOX", 1L, 12345L)
        assertEquals(emptyList(), dao.getRowsWithZeroDateMillis())
        assertEquals(12345L, db.messageDao().getMessage("me@x.com", "INBOX", 1L)!!.dateMillis)
    }

    @Test
    fun peekBackfill_findsEmptyPeekRowsAndUpdates() = runTest {
        val dao = db.queryDao()
        // Raw insert skips snippet computation, so the row is backfill-eligible.
        db.messageDao().insertMessagesRaw(listOf(message(1L, body = "needs peek").copy(peekContent = "")))
        assertEquals(listOf(1L), dao.getRowsWithEmptyPeek().map { it.id })
        dao.updatePeekContent("me@x.com", "INBOX", 1L, "needs peek")
        assertEquals(emptyList(), dao.getRowsWithEmptyPeek())
        assertEquals("needs peek", db.messageDao().getMessage("me@x.com", "INBOX", 1L)!!.peekContent)
    }

    @Test
    fun queryStore_delegatesToQueryDao() = runTest {
        val store = EmailQueryStore(db)
        db.messageDao().insertMessages(listOf(message(1L, body = "delegated body")))
        assertEquals(1, store.searchMessages("delegated").size)
        assertEquals(1, store.searchMessagesFlow("me@x.com", "INBOX", "delegated", 0L).first().size)
        assertEquals(1, store.getUnifiedMessagesFlow("INBOX", 0L).first().size)
        assertEquals(1, store.searchUnifiedMessagesFlow("INBOX", "delegated", 0L).first().size)
        assertEquals(1, store.getRecentUnifiedMessages().size)
        assertEquals(1, store.getRecentInboxMessages().size)
        assertEquals(1, store.getRecentUnifiedMessagesFlow().first().size)
        assertEquals(1, store.getMessagesPreviewFlow("me@x.com", "INBOX", 0L).first().size)
        assertEquals(1, store.searchMessagesPreviewFlow("me@x.com", "INBOX", "delegated", 0L).first().size)
        assertEquals(1, store.getUnifiedMessagesPreviewFlow("INBOX", 0L).first().size)
        assertEquals(1, store.searchUnifiedMessagesPreviewFlow("INBOX", "delegated", 0L).first().size)
        assertEquals(1, store.getRecentUnifiedPreview().size)
        db.messageDao().insertMessagesRaw(listOf(message(2L, body = "x").copy(dateMillis = 0L, peekContent = "")))
        assertEquals(1, store.getRowsWithZeroDateMillis().size)
        store.updateDateMillis("me@x.com", "INBOX", 2L, 77L)
        assertEquals(77L, db.messageDao().getMessage("me@x.com", "INBOX", 2L)!!.dateMillis)
        assertEquals(1, store.getRowsWithEmptyPeek().size)
        store.updatePeekContent("me@x.com", "INBOX", 2L, "x")
        assertEquals(emptyList(), store.getRowsWithEmptyPeek())
    }

    // ---------- outbox / drafts ----------

    @Test
    fun outbox_insertCountUpdateAttemptAndDelete() = runTest {
        val dao = db.outboxDao()
        assertEquals(0, dao.getOutboxCount())
        val id = dao.insertOutboxEntry(
            OutboxEntry(accountEmail = "me@x.com", to = "a@b.com", subject = "s", body = "b")
        )
        assertEquals(1, dao.getOutboxCount())
        assertEquals(1, dao.getOutbox().size)
        assertEquals(1, dao.getOutboxFlow().first().size)
        dao.updateOutboxAttempt(id, "timeout", 2, 999L)
        val back = dao.getOutbox().single()
        assertEquals("timeout", back.lastError)
        assertEquals(2, back.attemptCount)
        assertEquals(999L, back.lastAttemptAt)
        dao.deleteOutboxEntry(back)
        assertEquals(0, dao.getOutboxCount())
    }

    @Test
    fun drafts_insertGetDeleteAndFlow() = runTest {
        val dao = db.outboxDao()
        val id = dao.insertDraft(DraftEntry(accountEmail = "me@x.com", subject = "draft"))
        assertEquals("draft", dao.getDraft(id)!!.subject)
        assertNull(dao.getDraft(4242L))
        assertEquals(1, dao.getDraftsFlow().first().size)
        dao.deleteDraftById(id)
        assertNull(dao.getDraft(id))
        assertEquals(emptyList(), dao.getDraftsFlow().awaitList { it.isEmpty() })
    }
}
