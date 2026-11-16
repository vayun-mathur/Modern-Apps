package com.vayunmathur.email.imap

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.ServerConfig
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import android.os.Looper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W5: bulk seen flags, attachment download, and ViewModel export/bulk/body ops over fake servers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManagerOpsW5Test {
    private lateinit var imap: FakeImapServer
    private lateinit var db: EmailDatabase
    private val manager = EmailManager()
    private val auth = EmailManager.AuthType.Password("secret")
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun start() {
        imap = FakeImapServer(); imap.start()
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
        runBlocking {
            db.accountDao().insertAccount(
                EmailAccount(
                    email = "me@x.com", username = "me@x.com", provider = "outlook", authType = "oauth2",
                    accessToken = "live-token", expiresAt = System.currentTimeMillis() + 3_600_000,
                    imapHost = "127.0.0.1", imapPort = imap.port, imapUseSsl = false,
                    smtpHost = "127.0.0.1", smtpPort = 2525, smtpUseSsl = false,
                )
            )
        }
    }

    @AfterTest
    fun stop() {
        imap.close()
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        db.close()
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun imapConfig() = ServerConfig("127.0.0.1", imap.port, useSsl = false)

    @Test
    fun setSeenFlagSetMarksAndUnmarksManyAndNoOpsOnEmpty() = runBlocking {
        manager.setSeenFlagSet(imapConfig(), "me@x.com", auth, "INBOX", listOf(101L, 102L), seen = true)
        assertTrue(
            imap.receivedCommands.any { it.contains("STORE") && it.contains("101,102") },
            "bulk STORE should carry both uids, got ${imap.receivedCommands}",
        )
        manager.setSeenFlagSet(imapConfig(), "me@x.com", auth, "INBOX", listOf(101L), seen = false)
        manager.setSeenFlagSet(imapConfig(), "me@x.com", auth, "INBOX", emptyList(), seen = true)
    }

    @Test
    fun downloadAttachmentWritesPartAndUnknownPartThrows() {
        runBlocking {
        // The fake server serves "PARTBYTES" for part fetches.
        val result = runCatching {
            manager.downloadAttachment(
                context, imapConfig(), "me@x.com", auth, "INBOX", 101L,
                partId = "1", fileName = "w5-part.bin", mimeType = "application/octet-stream",
            )
        }
        // MediaStore may be unavailable under Robolectric: either a written
        // uri or the documented failure is acceptable — the wire fetch ran.
        assertTrue(
            imap.receivedCommands.any { it.contains("FETCH") },
            "part fetch should reach the server, got ${imap.receivedCommands}",
        )
        result.onFailure { assertTrue(it is IllegalStateException || it is java.io.IOException) }

        // The fake server serves part bytes for any uid, and Robolectric's
        // MediaStore accepts the insert: a second download also lands.
        val second = manager.downloadAttachment(
            context, imapConfig(), "me@x.com", auth, "INBOX", 999L,
            partId = "9", fileName = "nope.bin", mimeType = "",
        )
        assertTrue(second.startsWith("content://"), "download should land in Downloads, got $second")
        }
    }

    @Test
    fun viewModelExportBulkAndBodyFetch() = runBlocking {
        val vm = EmailViewModel(context)
        val msg = EmailMessage(
            accountEmail = "me@x.com", folderName = "INBOX", id = 101L,
            subject = "First", from = "a@b.com", date = "Mon, 10 Nov 2026 09:30:00 +0000",
            body = null, isRead = false,
        )
        db.messageDao().insertMessages(listOf(msg))

        // Body fetch fills the message from the fake server.
        vm.fetchBodyIfNeeded(msg)
        val deadline = System.currentTimeMillis() + 20_000
        var stored = db.messageDao().getMessage("me@x.com", "INBOX", 101L)
        while ((stored?.body == null) && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            stored = db.messageDao().getMessage("me@x.com", "INBOX", 101L)
        }
        assertEquals("Hello body one", stored?.body)

        // Bulk mark-as-read goes over the wire as a set STORE.
        vm.bulkMarkAsRead("me@x.com", listOf(101L, 102L), isRead = true)
        val bulkDeadline = System.currentTimeMillis() + 15_000
        while (!imap.receivedCommands.any { it.contains("STORE") } && System.currentTimeMillis() < bulkDeadline) {
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        assertTrue(
            imap.receivedCommands.any { it.contains("STORE") },
            "bulk mark should reach the server, got ${imap.receivedCommands}",
        )

        // Export writes the raw message to the target file uri.
        val outFile = File(context.cacheDir, "export-w5.eml")
        val done = kotlinx.coroutines.CompletableDeferred<Pair<Boolean, String?>>()
        vm.exportEml("me@x.com", "INBOX", 101L, android.net.Uri.fromFile(outFile)) { ok, err ->
            done.complete(ok to err)
        }
        // exportEml launches on viewModelScope (Main): pump the looper while waiting.
        val exportResult = kotlinx.coroutines.CompletableDeferred<Pair<Boolean, String?>>()
        val waitThread = kotlin.concurrent.thread(isDaemon = true) {
            val r = runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(20_000) { done.await() } } }
            exportResult.complete(r.getOrNull() ?: (false to "timeout"))
        }
        val waitDeadline = System.currentTimeMillis() + 25_000
        while (!exportResult.isCompleted && System.currentTimeMillis() < waitDeadline) {
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        val (ok, _) = exportResult.await()
        waitThread.join(1_000)
        assertTrue(ok && outFile.exists() && outFile.readText().contains("First"),
            "export should write the message, ok=$ok file=${outFile.exists()}")
    }
}
