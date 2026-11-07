package com.vayunmathur.email.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.email.data.DraftEntry
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.platform.EmailViewModel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real [ComposerScreen] against a [EmailViewModel] backed by a
 * pre-seeded in-memory database. The composer is the addressing + send path:
 * a miswired field drops recipients, a broken draft load loses the draft, a
 * dead schedule button strands timed mail — each test asserts the rendered
 * field or the recorded effect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerScreenTest {

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
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
        vm = EmailViewModel(context)
        runBlocking {
            db.accountDao().insertAccount(EmailAccount(email = "me@x.com"))
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

    @Test
    fun `fresh compose shows initial values and subject field`() {
        var backs = 0
        composeRule.setContent {
            ComposerScreen(
                viewModel = vm,
                initialTo = "a@b.com",
                initialSubject = "Hello",
                onBack = { backs++ },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("a@b.com").assertIsDisplayed()
        composeRule.onNodeWithText("Hello").assertIsDisplayed()
        assertEquals(0, backs)
    }

    @Test
    fun `cc bcc toggle reveals the extra fields`() {
        composeRule.setContent {
            ComposerScreen(viewModel = vm, onBack = {})
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cc/Bcc").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cc").assertIsDisplayed()
        composeRule.onNodeWithText("Bcc").assertIsDisplayed()
    }

    @Test
    fun `subject field accepts input`() {
        composeRule.setContent {
            ComposerScreen(viewModel = vm, initialSubject = "", onBack = {})
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Subject", substring = true).performTextInput("Trip")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Trip").assertIsDisplayed()
    }

    @Test
    fun `draft resumes its saved fields`() {
        val id = runBlocking {
            db.outboxDao().insertDraft(
                DraftEntry(accountEmail = "me@x.com", to = "a@b.com", subject = "Saved", body = "draft body")
            )
        }
        composeRule.setContent {
            ComposerScreen(viewModel = vm, draftId = id, onBack = {})
        }
        // The draft load is a LaunchedEffect: wait for the fields to land.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Saved").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Saved").assertIsDisplayed()
        composeRule.onNodeWithText("a@b.com").assertIsDisplayed()
    }

    @Test
    fun `schedule menu offers timed-send options`() {
        composeRule.setContent {
            ComposerScreen(viewModel = vm, initialTo = "a@b.com", initialSubject = "Hi", onBack = {})
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Later").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("In 1 hour").assertIsDisplayed()
    }
}
