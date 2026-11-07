package com.vayunmathur.email.screens

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.imap.FakeImapServer
import com.vayunmathur.email.platform.EmailViewModel
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [EmailViewModel] server-backed paths against the fake IMAP server:
 * delete/mark-read sync, lazy body fetch, CID loading and attachment
 * download failure all go through the account's configured host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailViewModelNetworkTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var server: FakeImapServer
    private lateinit var vm: EmailViewModel

    private fun message(uid: Long, body: String? = null) = EmailMessage(
        accountEmail = "me@x.com", folderName = "INBOX", id = uid,
        subject = "s$uid", from = "f@x.com", date = "d", dateMillis = uid, body = body,
    )

    @BeforeTest
    fun open() {
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
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
                    email = "me@x.com", provider = "outlook", authType = "oauth2",
                    accessToken = "live-token", expiresAt = System.currentTimeMillis() + 3_600_000,
                    imapHost = "127.0.0.1", imapPort = server.port, imapUseSsl = false,
                ),
            )
            db.messageDao().insertMessages(listOf(message(101L), message(102L, body = "already")))
        }
        vm = EmailViewModel(context)
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

    private fun await(check: () -> Boolean) {
        // Pump the Robolectric main looper so viewModelScope work runs.
        // (A compose rule's waitForIdle() can park forever here with no
        // composition set; see EmailViewModelLocalTest.pumpMain.)
        repeat(600) {
            shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(10)
        }
    }

    @Test
    fun `delete message removes the row locally`() {
        vm.deleteMessage("me@x.com", "INBOX", 101L)
        await {
            runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 101L) } == null &&
                server.receivedCommands.any { it.contains("EXPUNGE") }
        }
        assertEquals(null, runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 101L) })
        assertTrue(server.receivedCommands.any { it.contains("EXPUNGE") })
    }

    @Test
    fun `mark as read flips locally and stores the seen flag`() {
        vm.markAsRead("me@x.com", "INBOX", 101L, true)
        await {
            runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 101L) }?.isRead == true &&
                server.receivedCommands.any { it.contains("STORE") }
        }
        assertEquals(true, runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 101L) }?.isRead)
        assertTrue(server.receivedCommands.any { it.contains("STORE") })
    }

    @Test
    fun `snooze hides the message until its time`() {
        vm.snoozeMessage("me@x.com", "INBOX", 101L, System.currentTimeMillis() + 60_000)
        await { runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 101L) }?.snoozedUntil ?: 0 > 0 }
        assertTrue(runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 101L) }!!.snoozedUntil > 0)
    }

    @Test
    fun `fetch body if needed downloads and stores the body`() {
        vm.fetchBodyIfNeeded(runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 101L) }!!)
        await { runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 101L) }?.body != null }
        assertEquals("Hello body one", runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 101L) }?.body)
    }

    @Test
    fun `fetch body is a no-op when the body is present`() {
        vm.fetchBodyIfNeeded(runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 102L) }!!)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("already", runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 102L) }?.body)
    }

    @Test
    fun `export eml streams the raw message to the destination`() {
        val target = File(context.cacheDir, "exported.eml")
        var result: Pair<Boolean, String?>? = null
        vm.exportEml("me@x.com", "INBOX", 101L, Uri.fromFile(target)) { ok, err -> result = ok to err }
        await { result != null }
        assertEquals(true, result?.first)
        assertEquals(null, result?.second)
        val text = target.readText()
        assertTrue(text.contains("Hello body one"), text.take(400))
    }

    @Test
    fun `export eml for an unknown account reports the failure`() {
        val target = File(context.cacheDir, "exported-none.eml")
        var result: Pair<Boolean, String?>? = null
        vm.exportEml("ghost@x.com", "INBOX", 101L, Uri.fromFile(target)) { ok, err -> result = ok to err }
        await { result != null }
        assertEquals(false, result?.first)
        assertEquals("Account not found", result?.second)
    }

    @Test
    fun `one-click unsubscribe posts the one-click body to a local server`() {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var postedBody = ""
        var responseCode = 200
        http.createContext("/unsub") { exchange ->
            postedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            exchange.sendResponseHeaders(responseCode, -1)
            exchange.close()
        }
        http.start()
        try {
            var ok: Boolean? = null
            vm.oneClickUnsubscribe("http://127.0.0.1:${http.address.port}/unsub") { ok = it }
            await { ok != null }
            assertEquals(true, ok)
            assertEquals("List-Unsubscribe=One-Click", postedBody)

            var ok2: Boolean? = null
            responseCode = 500
            vm.oneClickUnsubscribe("http://127.0.0.1:${http.address.port}/unsub") { ok2 = it }
            await { ok2 != null }
            assertEquals(false, ok2)
        } finally {
            http.stop(0)
        }
    }

    @Test
    fun `one-click unsubscribe to an unreachable url reports false`() {
        var ok: Boolean? = null
        vm.oneClickUnsubscribe("http://127.0.0.1:1/unsub") { ok = it }
        await { ok != null }
        assertEquals(false, ok)
    }

    @Test
    fun `download attachment fetches the part through the account server`() {
        runBlocking {
            db.messageDao().insertAttachments(
                listOf(Attachment("me@x.com", "INBOX", 101L, "1", "part.bin", "application/octet-stream", 9L))
            )
        }
        // selectedAccount is a WhileSubscribed StateFlow: subscribe so it
        // resolves the seeded account, pumping Main via await().
        val collectJob: Job = CoroutineScope(Dispatchers.Default).launch {
            vm.selectedAccount.collect {}
        }
        try {
            await { vm.selectedAccount.value?.email == "me@x.com" }
            val attachment = Attachment("me@x.com", "INBOX", 101L, "1", "part.bin", "application/octet-stream", 9L)
            var successPath: String? = null
            var errorMessage: String? = null
            vm.downloadAttachment(
                attachment,
                onSuccess = { successPath = it },
                onError = { errorMessage = it },
            )
            await { successPath != null || errorMessage != null }
            // The part bytes came from the fake server either way; the
            // platform Downloads insert decides success vs surfaced error.
            assertTrue(server.receivedCommands.any { it.contains("FETCH") }, server.receivedCommands.toString())
            if (successPath != null) {
                val stored = runBlocking { db.messageDao().getAttachments("me@x.com", 101L) }.single()
                assertEquals(successPath, stored.localUri)
            } else {
                assertTrue(!errorMessage.isNullOrBlank())
            }
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun `load cid map returns empty for a plain message`() = runBlocking {
        val map = vm.loadCidMap(context, db.messageDao().getMessage("me@x.com", "INBOX", 101L)!!)
        assertTrue(map.isEmpty())
    }
}
