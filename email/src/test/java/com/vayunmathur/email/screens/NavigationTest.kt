package com.vayunmathur.email.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.email.Navigation
import com.vayunmathur.email.Route
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.IntentState
import com.vayunmathur.email.ui.MainContent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real [Navigation] graph under Robolectric: starts on the message list, and
 * an [IntentState] route (the deep-link/share entry point) pushes its screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

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
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
        runBlocking { db.accountDao().insertAccount(EmailAccount(email = "me@x.com")) }
        vm = EmailViewModel(context)
        IntentState.navigationRoute = null
    }

    @AfterTest
    fun close() {
        IntentState.navigationRoute = null
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
        db.close()
    }

    @Test
    fun `navigation starts on message list and intent route pushes settings`() {
        composeRule.setContent { Navigation(viewModel = vm) }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("me@x.com", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("INBOX", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        IntentState.navigationRoute = Route.Settings
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Blocked senders", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Blocked senders", substring = true).performScrollTo().assertIsDisplayed()
    }

    private fun navigateAndAwait(route: Route, marker: String) {
        IntentState.navigationRoute = route
        composeRule.setContent { Navigation(viewModel = vm) }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(marker, substring = true).assertExists()
    }

    @Test
    fun `composer route opens the composer with its prefilled subject`() {
        navigateAndAwait(Route.Composer(to = "you@y.com", subject = "Nav subject"), "Nav subject")
    }

    @Test
    fun `outbox route opens the outbox`() {
        navigateAndAwait(Route.Outbox, "Outbox is empty")
    }

    @Test
    fun `drafts route opens the drafts list`() {
        navigateAndAwait(Route.Drafts, "No drafts")
    }

    @Test
    fun `add account route opens the provider picker`() {
        navigateAndAwait(Route.AddAccount, "Gmail")
    }

    @Test
    fun `eml viewer route opens the viewer error state for a bad uri`() {
        navigateAndAwait(Route.EmlViewer("content://invalid/nonexistent.eml"), "Couldn't open this .eml file")
    }

    @Test
    fun `message thread route opens the seeded conversation`() {
        runBlocking {
            db.messageDao().insertMessages(
                listOf(
                    EmailMessage(
                        accountEmail = "me@x.com", folderName = "INBOX", id = 7,
                        subject = "Thread subject", from = "f@x.com", date = "d",
                        dateMillis = 7, threadId = "t-7", body = "thread body",
                    )
                )
            )
        }
        navigateAndAwait(Route.MessageThread("me@x.com", "t-7"), "Thread subject")
    }

    @Test
    fun `main content shows navigation when an account exists`() {
        composeRule.setContent { MainContent(viewModel = vm) }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("INBOX", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("me@x.com", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
