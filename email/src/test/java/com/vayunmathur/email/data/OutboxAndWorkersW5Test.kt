package com.vayunmathur.email.data

import android.app.Application
import android.net.Uri
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.vayunmathur.email.Route
import com.vayunmathur.email.ui.composer.InlineAttachment
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W5: OutboxManager file copying, idle-retry worker, and Route serialization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxAndWorkersW5Test {
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

    @Test
    fun enqueueCopiesFileAttachmentsAndInlineImages() = runBlocking {
        val attachment = File(context.cacheDir, "report-w5.pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val inlineFile = File(context.cacheDir, "logo-w5.png").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val entry = OutboxManager.enqueue(
            context,
            accountEmail = "me@x.com",
            to = "a@b.com",
            subject = "Files",
            body = "See attached",
            cc = "c@d.com",
            attachments = listOf(Uri.fromFile(attachment), Uri.fromFile(File(context.cacheDir, "missing-w5.bin"))),
            inlineImages = listOf(
                InlineAttachment(
                    cid = "logo1",
                    uri = Uri.fromFile(inlineFile),
                    mimeType = "image/png",
                    fileName = "dir/logo.png",
                )
            ),
        )
        val stored = db.outboxDao().getOutbox().single()
        assertEquals("Files", stored.subject)
        assertEquals(entry.id, stored.id)
        val paths = OutboxSendWorker.decodePaths(stored.attachmentLocalPaths)
        assertEquals(1, paths.size, "only the existing attachment should be copied, got $paths")
        assertTrue(File(paths[0]).readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        val inline = OutboxSendWorker.decodeInline(stored.inlineImageJson)
        assertEquals(1, inline.size)
        assertEquals("logo1", inline[0].cid)
        assertTrue(File(inline[0].path).exists())

        OutboxManager.delete(context, stored)
        assertEquals(0, db.outboxDao().getOutboxCount())
    }

    @Test
    fun enqueueScheduledEntryIsStoredWithErrorAndSchedule() = runBlocking {
        val entry = OutboxManager.enqueue(
            context,
            accountEmail = "me@x.com",
            to = "a@b.com",
            subject = "Later",
            body = "b",
            initialError = "queued offline",
            scheduledAt = System.currentTimeMillis() + 3_600_000,
        )
        assertTrue(entry.scheduledAt > System.currentTimeMillis())
        assertEquals("queued offline", db.outboxDao().getOutbox().single().lastError)
    }

    @Test
    fun idleRetryWorkerAndSyncWorkerWithNoAccounts() = runBlocking {
        val retry = TestListenableWorkerBuilder<ImapIdleRetryWorker>(context).build()
        val retryResult = retry.doWork()
        assertTrue(
            retryResult == ListenableWorker.Result.success() || retryResult == ListenableWorker.Result.retry(),
            "idle retry should succeed or retry, got $retryResult",
        )
        val sync = TestListenableWorkerBuilder<EmailSyncWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), sync.doWork())
    }

    @Test
    fun routeSerializerRoundTripsEveryDestination() {
        val json = Json { classDiscriminator = "type" }
        val routes: List<Route> = listOf(
            Route.MessageList,
            Route.MessageThread("me@x.com", "thread-1"),
            Route.Composer("a@b.com", "Hi", "Body", "mid-1", "refs", 7L),
            Route.Composer(),
            Route.Outbox,
            Route.Drafts,
            Route.AddAccount,
            Route.Settings,
            Route.EmlViewer("content://mail/1"),
        )
        for (route in routes) {
            val encoded = json.encodeToString(Route.serializer(), route)
            val decoded = json.decodeFromString(Route.serializer(), encoded)
            assertEquals(route, decoded, "route should round-trip via $encoded")
        }
        // The serializer itself must describe the sealed hierarchy.
        assertTrue(Route.serializer().descriptor.elementsCount > 0)
    }
}
