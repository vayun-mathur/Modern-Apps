package com.vayunmathur.email.data

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Worker `doWork` paths against an in-memory database: snooze wake-up,
 * empty-outbox flush, missing-account outbox failure bookkeeping, and the
 * no-account sync no-op. Network attempts are absent or fail fast locally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkerDoWorkTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase

    @BeforeTest
    fun open() {
        try {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
    }

    @AfterTest
    fun close() {
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        db.close()
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun message(uid: Long, snoozedUntil: Long = 0, isRead: Boolean = true) = EmailMessage(
        accountEmail = "me@x.com", folderName = "INBOX", id = uid,
        subject = "s$uid", from = "f@x.com", date = "d", dateMillis = uid,
        isRead = isRead, snoozedUntil = snoozedUntil,
    )

    @Test
    fun `snooze worker wakes due messages only`() = runBlocking {
        db.messageDao().insertMessages(
            listOf(
                message(1L, snoozedUntil = System.currentTimeMillis() - 1_000),
                message(2L, snoozedUntil = System.currentTimeMillis() + 60_000),
            )
        )
        val worker = TestListenableWorkerBuilder<SnoozeWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val woken = db.messageDao().getMessage("me@x.com", "INBOX", 1L)
        val still = db.messageDao().getMessage("me@x.com", "INBOX", 2L)
        assertEquals(0L, woken?.snoozedUntil)
        assertEquals(false, woken?.isRead)
        assertTrue((still?.snoozedUntil ?: 0) > System.currentTimeMillis())
    }

    @Test
    fun `outbox worker succeeds with an empty outbox`() = runBlocking {
        val worker = TestListenableWorkerBuilder<OutboxSendWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test
    fun `outbox worker records failure for a missing account`() = runBlocking {
        db.outboxDao().insertOutboxEntry(
            OutboxEntry(accountEmail = "ghost@x.com", to = "a@b.com", subject = "Hi", body = "b"),
        )
        val worker = TestListenableWorkerBuilder<OutboxSendWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val entry = db.outboxDao().getOutbox().single()
        assertEquals(1, entry.attemptCount)
        assertTrue(entry.lastError!!.contains("No account"))
    }

    @Test
    fun `outbox worker defers a future scheduled entry without attempting it`() = runBlocking {
        db.outboxDao().insertOutboxEntry(
            OutboxEntry(
                accountEmail = "ghost@x.com", to = "a@b.com", subject = "Later", body = "b",
                scheduledAt = System.currentTimeMillis() + 3_600_000,
            ),
        )
        val worker = TestListenableWorkerBuilder<OutboxSendWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, db.outboxDao().getOutbox().single().attemptCount)
    }

    @Test
    fun `sync worker succeeds with no accounts`() = runBlocking {
        val worker = TestListenableWorkerBuilder<EmailSyncWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test
    fun `outbox manager enqueue persists and delete removes`() = runBlocking {
        try {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        val entry = OutboxManager.enqueue(
            context = context,
            accountEmail = "me@x.com",
            to = "a@b.com",
            subject = "Queued",
            body = "body",
        )
        val stored = db.outboxDao().getOutbox().single()
        assertEquals(entry.id, stored.id)
        assertEquals("Queued", stored.subject)
        OutboxManager.delete(context, stored)
        assertTrue(db.outboxDao().getOutbox().isEmpty())
    }
}
