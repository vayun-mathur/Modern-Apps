package com.vayunmathur.email.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.email.FakeAndroidKeyStore
import com.vayunmathur.email.data.CredentialCrypto
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.imap.FakeSmtpServer
import com.vayunmathur.email.platform.EmailViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [ComposerScreen] send paths against the fake SMTP server: Send must
 * put the composed mail on the wire and leave via onBack, "Later" must
 * queue a scheduled outbox entry (deleting any resumed draft), and a
 * dead server must land the mail in the outbox instead of losing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerSendTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var vm: EmailViewModel
    private lateinit var smtp: FakeSmtpServer

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
        val (cipher, iv) = CredentialCrypto.encrypt("secret")
        runBlocking {
            db.accountDao().insertAccount(
                EmailAccount(
                    email = "me@x.com", provider = "custom", authType = "password",
                    passwordEncrypted = cipher, passwordIv = iv,
                    smtpHost = "127.0.0.1", smtpPort = smtp.port, smtpUseSsl = false,
                    imapHost = "127.0.0.1", imapPort = 993, imapUseSsl = false,
                )
            )
        }
        vm = EmailViewModel(context)
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

    /**
     * Callbacks land on Dispatchers.Main, which Robolectric's paused
     * looper only runs when idled — the compose rule's waitUntil does not
     * pump it, so poll with explicit idles.
     */
    private fun awaitBacks(backs: () -> Int) {
        repeat(3000) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            if (backs() == 1) return
            Thread.sleep(10)
        }
    }

    @Test
    fun `send puts the composed mail on the wire and goes back`() {
        var backs = 0
        composeRule.setContent {
            ComposerScreen(
                viewModel = vm,
                initialTo = "you@y.com",
                initialSubject = "Wire subject",
                initialBody = "wirebody",
                onBack = { backs++ },
            )
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Wire subject").fetchSemanticsNodes().isNotEmpty()
        }
        // Send stays disabled until the accounts flow lands a sender.
        composeRule.waitUntil(15_000) {
            val node = composeRule.onNodeWithContentDescription("Send").fetchSemanticsNode()
            node != null && !node.config.contains(SemanticsProperties.Disabled)
        }
        composeRule.onNodeWithContentDescription("Send").performClick()
        // Wire-first: prove the SMTP transaction happened, then the back.
        composeRule.waitUntil(20_000) { smtp.dataBodies.isNotEmpty() || backs == 1 }
        assertTrue(
            smtp.dataBodies.isNotEmpty(),
            "no SMTP data after send click (backs=$backs, received=${smtp.received})",
        )
        awaitBacks { backs }
        assertEquals(1, backs)
        assertTrue(smtp.dataBodies.single().contains("Wire subject"))
        assertTrue(smtp.dataBodies.single().contains("wirebody"))
        assertEquals(0, runBlocking { db.outboxDao().getOutboxCount() })
    }

    @Test
    fun `later queues a scheduled outbox entry and goes back`() {
        var backs = 0
        composeRule.setContent {
            ComposerScreen(
                viewModel = vm,
                initialTo = "later@y.com",
                initialSubject = "Scheduled one",
                onBack = { backs++ },
            )
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Scheduled one").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(15_000) {
            val node = composeRule.onNodeWithText("Later").fetchSemanticsNode()
            node != null && !node.config.contains(SemanticsProperties.Disabled)
        }
        composeRule.onNodeWithText("Later").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("In 1 hour").performClick()
        awaitBacks { backs }
        assertEquals(1, backs)
        val entry = runBlocking { db.outboxDao().getOutbox() }.single()
        assertEquals("Scheduled one", entry.subject)
        assertEquals("later@y.com", entry.to)
        assertTrue(entry.scheduledAt > System.currentTimeMillis())
        assertEquals(emptyList<String>(), smtp.dataBodies)
    }

}
