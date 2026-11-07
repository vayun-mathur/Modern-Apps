package com.vayunmathur.email.screens

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.platform.EmailViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives the real [EmailViewModel]'s local-only paths against a pre-seeded
 * in-memory database: message/thread/attachment reads, bulk-read, signatures
 * and logout. These never touch the network, but a wrong DAO call shows the
 * wrong thread or leaks an account after logout — each test asserts the exact
 * rows and flows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailViewModelLocalTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var vm: EmailViewModel

    private fun message(uid: Long, thread: String = "t1", isRead: Boolean = false) = EmailMessage(
        accountEmail = "me@x.com", folderName = "INBOX", id = uid,
        subject = "s$uid", from = "f@x.com", date = "d", dateMillis = uid,
        threadId = thread, isRead = isRead,
    )

    @BeforeTest
    fun open() {
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
        // Seed before creating the VM: its init picks the first account, so
        // inserting afterwards races that coroutine and leaves no selection.
        runBlocking {
            db.accountDao().insertAccount(EmailAccount(email = "me@x.com"))
        }
        vm = EmailViewModel(context)
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

    /**
     * Pump the Robolectric main looper so viewModelScope (Dispatchers.Main)
     * work runs. Earlier revisions used a compose rule's waitForIdle() for
     * this; with no composition set that can park forever inside
     * ComposeRootRegistry.waitForComposeRoots, hanging the whole suite.
     */
    private fun pumpMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `getMessage returns the stored row`() {
        runBlocking {
            db.messageDao().insertMessages(listOf(message(1L)))
            val row = vm.getMessage("me@x.com", "INBOX", 1L)
            assertEquals("s1", row?.subject)
            assertNull(vm.getMessage("me@x.com", "INBOX", 999L))
        }
    }

    @Test
    fun `getThread returns only that thread's messages`() {
        runBlocking {
            db.messageDao().insertMessages(listOf(message(1L, "t1"), message(2L, "t1"), message(3L, "t2")))
            val thread = vm.getThread("me@x.com", "t1").first()
            assertEquals(listOf(1L, 2L), thread.map { it.id }.sorted())
        }
    }

    @Test
    fun `getAttachments returns the stored files`() {
        runBlocking {
            db.messageDao().insertMessages(listOf(message(1L)))
            db.messageDao().insertAttachments(
                listOf(
                    Attachment("me@x.com", "INBOX", 1L, "0", "a.pdf", "application/pdf", 10L),
                    Attachment("me@x.com", "INBOX", 1L, "1", "b.pdf", "application/pdf", 20L),
                )
            )
            val atts = vm.getAttachments("me@x.com", 1L)
            assertEquals(listOf("a.pdf", "b.pdf"), atts.map { it.fileName }.sorted())
        }
    }

    @Test
    fun `bulk mark-as-read flips rows and clears selection`() {
        runBlocking { db.messageDao().insertMessages(listOf(message(1L), message(2L))) }
        vm.toggleMessageSelection(1L)
        vm.toggleMessageSelection(2L)
        vm.bulkMarkAsRead("me@x.com", listOf(1L, 2L), true)
        repeat(600) {
            pumpMain()
            if (runBlocking { vm.selectedMessageUids.first() }.isEmpty()) return@repeat
            Thread.sleep(10)
        }
        assertEquals(emptySet(), runBlocking { vm.selectedMessageUids.first() })
        repeat(600) {
            pumpMain()
            val rows = runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 1L) }
            if (rows?.isRead == true) return@repeat
            Thread.sleep(10)
        }
        assertEquals(true, runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 2L) }?.isRead)
    }

    @Test
    fun `setSignature persists on the account`() {
        vm.setSignature("me@x.com", "Best, Me")
        repeat(600) {
            pumpMain()
            val sig = runBlocking { db.accountDao().getAccountByEmail("me@x.com") }?.signature
            if (sig == "Best, Me") return@repeat
            Thread.sleep(10)
        }
        assertEquals("Best, Me", runBlocking { db.accountDao().getAccountByEmail("me@x.com") }?.signature)
    }

    @Test
    fun `blockSender extracts the bare address from common header shapes`() {
        vm.blockSender("Boss Man <Boss@Work.COM>")
        vm.blockSender("plain@x.com")
        vm.blockSender("Newsletter (via lists) <news@list.io>")
        vm.blockSender("No Address Here")
        vm.blockSender("")
        var addresses = emptyList<String>()
        repeat(600) {
            pumpMain()
            addresses = runBlocking {
                db.accountDao().getBlockedSendersFlow().first().map { it.address }
            }
            if (addresses.size == 3) return@repeat
            Thread.sleep(10)
        }
        assertEquals(listOf("boss@work.com", "news@list.io", "plain@x.com"), addresses.sorted())
    }

    @Test
    fun `logout removes the last account and clears selection`() {
        // Wait for init to pick the seeded account.
        repeat(600) {
            pumpMain()
            if (runBlocking { vm.selectedAccountEmail.first() } != null) return@repeat
            Thread.sleep(10)
        }
        assertEquals("me@x.com", runBlocking { vm.selectedAccountEmail.first() })
        vm.logout(context)
        repeat(600) {
            pumpMain()
            if (runBlocking { db.accountDao().getAccounts() }.isEmpty()) return@repeat
            Thread.sleep(10)
        }
        assertEquals(emptyList(), runBlocking { db.accountDao().getAccounts() })
        assertEquals(null, runBlocking { vm.selectedAccountEmail.first() })
    }
}
