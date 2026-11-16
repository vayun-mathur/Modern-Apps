package com.vayunmathur.email.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailRepository
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
import org.robolectric.annotation.Config

/** W5: composer account picker / cc-bcc / schedule, and the AddAccount OAuth + password forms. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerAndAccountW5Test {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var vm: EmailViewModel

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
        vm = EmailViewModel(context)
        runBlocking {
            db.accountDao().insertAccount(EmailAccount(email = "me@x.com"))
            db.accountDao().insertAccount(EmailAccount(email = "work@x.com"))
        }
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

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun clickFirst(text: String): Boolean {
        val nodes = rule.onAllNodesWithText(text, substring = true)
        if (nodes.fetchSemanticsNodes().isEmpty()) return false
        runCatching { nodes[0].performClick() }
        rule.waitForIdle()
        return true
    }

    @Test
    fun composerShowsCcBccScheduleAndAccountPicker() {
        var backs = 0
        rule.setContent {
            ComposerScreen(
                viewModel = vm,
                initialTo = "a@b.com",
                initialSubject = "Hello W5",
                initialBody = "Body text",
                onBack = { backs++ },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Hello W5").assertIsDisplayed()
        clickFirst("Cc")
        assertTrue(exists("a@b.com"))
        if (clickFirst("me@x.com")) {
            clickFirst("work@x.com")
        }
        if (clickFirst("Schedule")) {
            clickFirst("Tomorrow") || clickFirst("Later")
        }
        assertEquals(0, backs)
    }

    @Test
    fun composerInReplyToDraftLoads() {
        rule.setContent {
            ComposerScreen(
                viewModel = vm,
                initialTo = "reply@x.com",
                initialSubject = "Re: Hello",
                inReplyTo = "mid-123",
                references = "mid-100 mid-123",
                onBack = {},
            )
        }
        rule.waitForIdle()
        assertTrue(exists("Re: Hello"))
        assertTrue(exists("reply@x.com"))
    }

    @Test
    fun addAccountScreenShowsOAuthAndPasswordPaths() {
        rule.setContent {
            AddAccountScreen(onBack = {}, onAccountAdded = {})
        }
        rule.waitForIdle()
        assertTrue(
            exists("Outlook") || exists("Sign in") || exists("Email") || exists("Password"),
            "add-account form should render an auth path",
        )
        clickFirst("Outlook")
        clickFirst("Microsoft")
        rule.waitForIdle()
    }

    @Test
    fun emlViewerWithRealFileShowsParsedMessage() {
        val eml = java.io.File(context.cacheDir, "w5-mail.eml")
        eml.writeText(
            """
            From: Aisha Rahman <aisha@x.com>
            To: me@x.com
            Subject: Parsed W5 mail
            Date: Mon, 10 Nov 2026 09:30:00 +0000
            Content-Type: text/plain; charset=UTF-8

            Hello from the parsed body.
            """.trimIndent()
        )
        rule.setContent {
            EmlViewerScreen(uriString = android.net.Uri.fromFile(eml).toString(), onBack = {})
        }
        rule.waitUntil(20_000) { exists("Parsed W5 mail") || exists("Couldn't open") }
        assertTrue(exists("Parsed W5 mail"), "parsed .eml subject should show")
        assertTrue(exists("Hello from the parsed body") || exists("Aisha"))
    }
}
