package com.vayunmathur.email.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real [EmailDrawerContent] with literal data (it takes no
 * ViewModel) and pins navigation clicks to the right callbacks.
 *
 * The drawer is the only path to folders, outbox, drafts and settings: a
 * miswired lambda strands the user on the inbox with no way to reach them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailDrawerContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val accounts = listOf(
        EmailAccount(email = "me@x.com"),
        EmailAccount(email = "you@y.com"),
    )
    private val folders = listOf(
        EmailFolder("me@x.com", "INBOX", "INBOX"),
        EmailFolder("me@x.com", "INBOX/Work", "Work", parentFullName = "INBOX"),
    )

    private class Calls {
        var account: String? = "unset"
        var folder: String? = "unset"
        var outbox = 0
        var system: String? = null
        var logout = 0
        var addAccount = 0
    }

    private fun render(
        calls: Calls,
        selectedAccount: String? = "me@x.com",
        outboxCount: Int = 0,
    ) {
        composeRule.setContent {
            EmailDrawerContent(
                accounts = accounts,
                selectedAccountEmail = selectedAccount,
                folders = folders,
                selectedFolderName = "INBOX",
                outboxCount = outboxCount,
                onSelectAccount = { calls.account = it },
                onSelectFolder = { calls.folder = it },
                onOpenOutbox = { calls.outbox++ },
                onOpenSystem = { calls.system = it },
                onLogout = { calls.logout++ },
                onAddAccount = { calls.addAccount++ },
            )
        }
    }

    @Test
    fun `accounts and folders render with selection`() {
        render(Calls())
        composeRule.onNodeWithText("me@x.com").assertIsDisplayed()
        composeRule.onNodeWithText("you@y.com").assertIsDisplayed()
        composeRule.onNodeWithText("INBOX").assertIsDisplayed()
        composeRule.onNodeWithText("Work").assertIsDisplayed()
    }

    @Test
    fun `account click reports the picked address`() {
        val calls = Calls()
        render(calls)
        composeRule.onNodeWithText("you@y.com").performClick()
        assertEquals("you@y.com", calls.account)
    }

    @Test
    fun `folder click reports the folder name`() {
        val calls = Calls()
        render(calls)
        composeRule.onNodeWithText("Work").performClick()
        assertEquals("INBOX/Work", calls.folder)
    }

    @Test
    fun `system rows and logout route to their callbacks`() {
        val calls = Calls()
        render(calls, outboxCount = 3)
        composeRule.onNodeWithText("Outbox", substring = true).performClick()
        assertEquals(1, calls.outbox)
        composeRule.onNodeWithText("Drafts", substring = true).performClick()
        assertEquals("drafts", calls.system)
        composeRule.onNodeWithText("Settings", substring = true).performClick()
        assertEquals("settings", calls.system)
    }

    @Test
    fun `unified inbox hides folders and logout`() {
        // No account selected: folder tree and logout have nothing to act on.
        render(Calls(), selectedAccount = null)
        composeRule.onNodeWithText("All Accounts").assertIsDisplayed()
        composeRule.onNodeWithText("Work").assertDoesNotExist()
    }

    @Test
    fun `add account routes to its callback`() {
        val calls = Calls()
        render(calls)
        composeRule.onNodeWithText("Add Account").performClick()
        assertEquals(1, calls.addAccount)
    }
}
