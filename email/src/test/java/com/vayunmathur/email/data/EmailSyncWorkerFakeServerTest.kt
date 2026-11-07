package com.vayunmathur.email.data

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.vayunmathur.email.imap.FakeImapServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [EmailSyncWorker] end-to-end against the fake IMAP server: an Outlook
 * OAuth account (stored live token, so no token endpoint is hit) syncs
 * folders and messages into Room, then backfills bodies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailSyncWorkerFakeServerTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var server: FakeImapServer

    @BeforeTest
    fun open() {
        try {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        server = FakeImapServer(); server.start()
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
        runBlocking {
            db.accountDao().insertAccount(
                EmailAccount(
                    email = "me@x.com",
                    provider = "outlook",
                    authType = "oauth2",
                    accessToken = "live-token",
                    expiresAt = System.currentTimeMillis() + 3_600_000,
                    imapHost = "127.0.0.1",
                    imapPort = server.port,
                    imapUseSsl = false,
                ),
            )
        }
    }

    @AfterTest
    fun close() {
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        db.close()
        server.close()
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test
    fun `sync worker pulls folders and messages from the fake server`() = runBlocking {
        val worker = TestListenableWorkerBuilder<EmailSyncWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val folders = db.accountDao().getFoldersFlow("me@x.com").first()
        assertEquals(listOf("Archive", "INBOX", "Sent"), folders.map { it.fullName }.sorted())

        val inbox = db.queryDao().getMessagesPreviewFlow("me@x.com", "INBOX", Long.MAX_VALUE).first()
        assertEquals(listOf("First", "Second"), inbox.map { it.subject }.sorted())

        // Body backfill fills the plain body for the fetched headers.
        val row = db.messageDao().getMessage("me@x.com", "INBOX", 101L)
        assertEquals("Hello body one", row?.body)
    }

    @Test
    fun `second sync does not duplicate already known messages`() = runBlocking {
        val worker = TestListenableWorkerBuilder<EmailSyncWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val inbox = db.queryDao().getMessagesPreviewFlow("me@x.com", "INBOX", Long.MAX_VALUE).first()
        assertEquals(2, inbox.size)
        assertEquals(listOf(101L, 102L), db.messageDao().getKnownUids("me@x.com", "INBOX").sorted())
    }

    @Test
    fun `non-inbox sync skips the inbox but syncs other folders`() = runBlocking {
        val worker = TestListenableWorkerBuilder<EmailSyncWorker>(context)
            .setInputData(Data.Builder().putBoolean("non_inbox_only", true).build())
            .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val inbox = db.queryDao().getMessagesPreviewFlow("me@x.com", "INBOX", Long.MAX_VALUE).first()
        assertEquals(emptyList(), inbox)
        val sent = db.queryDao().getMessagesPreviewFlow("me@x.com", "Sent", Long.MAX_VALUE).first()
        assertEquals(listOf("First", "Second"), sent.map { it.subject }.sorted())
        // Folders are still discovered and stored in both modes.
        assertEquals(3, db.accountDao().getFoldersFlow("me@x.com").first().size)
    }

    @Test
    fun `auth failure makes the worker retry and syncs nothing`() = runBlocking {
        server.failLogin = true
        val worker = TestListenableWorkerBuilder<EmailSyncWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(emptyList(), db.messageDao().getKnownUids("me@x.com", "INBOX"))
        assertEquals(emptyList(), db.accountDao().getFoldersFlow("me@x.com").first())
    }
}
