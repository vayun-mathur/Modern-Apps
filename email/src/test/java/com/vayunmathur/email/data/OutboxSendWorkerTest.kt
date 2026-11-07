package com.vayunmathur.email.data

import android.app.Application
import android.net.Uri
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.vayunmathur.email.FakeAndroidKeyStore
import com.vayunmathur.email.imap.FakeSmtpServer
import com.vayunmathur.email.ui.composer.InlineAttachment
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [OutboxSendWorker] flush and [OutboxManager] file handling: a due
 * entry must be sent over SMTP and removed (with its copied attachment
 * files), a failing entry must stay queued with the attempt recorded,
 * and enqueue must copy attachments/inline images into app-private
 * storage so the send still works after the source URI is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxSendWorkerTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var smtp: FakeSmtpServer

    private fun account(smtpPort: Int): EmailAccount {
        val (cipher, iv) = CredentialCrypto.encrypt("secret")
        return EmailAccount(
            email = "me@x.com", provider = "custom", authType = "password",
            passwordEncrypted = cipher, passwordIv = iv,
            smtpHost = "127.0.0.1", smtpPort = smtpPort, smtpUseSsl = false,
            imapHost = "127.0.0.1", imapPort = 993, imapUseSsl = false,
        )
    }

    @BeforeTest
    fun open() {
        FakeAndroidKeyStore.install()
        FakeAndroidKeyStore.clear()
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        smtp = FakeSmtpServer(); smtp.start()
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
        smtp.close()
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun worker(): OutboxSendWorker =
        TestListenableWorkerBuilder<OutboxSendWorker>(context).build()

    @Test
    fun `doWork sends a due entry and removes it`() = runBlocking {
        db.accountDao().insertAccount(account(smtp.port))
        val entry = OutboxManager.enqueue(
            context, accountEmail = "me@x.com", to = "you@y.com",
            subject = "Flush me", body = "flushbody",
        )
        assertEquals(1, db.outboxDao().getOutboxCount())
        val result = worker().doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertEquals(0, db.outboxDao().getOutboxCount())
        assertTrue(smtp.dataBodies.single().contains("Flush me"))
        assertTrue(smtp.dataBodies.single().contains("flushbody"))
        assertFalse(OutboxSendWorker.attachmentDirFor(context, entry.id).exists())
    }

    @Test
    fun `doWork records the attempt when the send fails`() = runBlocking {
        db.accountDao().insertAccount(account(smtpPort = 1))
        OutboxManager.enqueue(
            context, accountEmail = "me@x.com", to = "you@y.com",
            subject = "Stuck", body = "b",
        )
        val result = worker().doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        val rows = db.outboxDao().getOutbox()
        assertEquals(1, rows.size)
        assertEquals(1, rows.single().attemptCount)
        assertTrue(rows.single().lastError?.contains("Exception") == true, rows.single().lastError)
        assertTrue(rows.single().lastAttemptAt > 0)
    }

    @Test
    fun `enqueue copies attachments and inline images into outbox storage`() = runBlocking {
        val src = File(context.cacheDir, "notes.txt").apply { writeText("attachment-bytes") }
        val img = File(context.cacheDir, "pic.jpg").apply { writeText("jpeg-bytes") }
        val entry = OutboxManager.enqueue(
            context, accountEmail = "me@x.com", to = "a@b.com", subject = "Files", body = "b",
            attachments = listOf(Uri.fromFile(src)),
            inlineImages = listOf(
                InlineAttachment(cid = "img1@inline", uri = Uri.fromFile(img), mimeType = "image/jpeg", fileName = "pic.jpg")
            ),
            scheduledAt = System.currentTimeMillis() + 3_600_000,
        )
        val paths = OutboxSendWorker.decodePaths(entry.attachmentLocalPaths)
        assertEquals(1, paths.size)
        assertEquals("attachment-bytes", File(paths.single()).readText())
        val inline = OutboxSendWorker.decodeInline(entry.inlineImageJson)
        assertEquals(1, inline.size)
        assertEquals("img1@inline", inline.single().cid)
        assertEquals("pic.jpg", inline.single().name)
        assertEquals("jpeg-bytes", File(inline.single().path).readText())
        // Deleting the entry removes the copied files with it.
        OutboxManager.delete(context, entry)
        assertEquals(0, db.outboxDao().getOutboxCount())
        assertFalse(OutboxSendWorker.attachmentDirFor(context, entry.id).exists())
    }

    @Test
    fun `enqueue skips unreadable attachments instead of failing`() = runBlocking {
        val entry = OutboxManager.enqueue(
            context, accountEmail = "me@x.com", to = "a@b.com", subject = "Ghost", body = "b",
            attachments = listOf(Uri.fromFile(File(context.cacheDir, "does-not-exist.bin"))),
            scheduledAt = System.currentTimeMillis() + 3_600_000,
        )
        assertEquals(emptyList(), OutboxSendWorker.decodePaths(entry.attachmentLocalPaths))
        assertEquals(1, db.outboxDao().getOutboxCount())
    }

    @Test
    fun `path and inline codecs round-trip and tolerate garbage`() {
        assertEquals(listOf("/a/b.txt", "/c.txt"), OutboxSendWorker.decodePaths(OutboxSendWorker.encodePaths(listOf("/a/b.txt", "/c.txt"))))
        assertEquals(emptyList(), OutboxSendWorker.decodePaths("not json"))
        assertEquals(emptyList(), OutboxSendWorker.decodePaths(""))
        val entries = listOf(OutboxSendWorker.Companion.InlineJsonEntry("cid1", "/p", "image/png", "n.png"))
        assertEquals(entries, OutboxSendWorker.decodeInline(OutboxSendWorker.encodeInline(entries)))
        assertEquals(emptyList(), OutboxSendWorker.decodeInline("{broken"))
    }
}
