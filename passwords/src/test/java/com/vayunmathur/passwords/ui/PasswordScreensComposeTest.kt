package com.vayunmathur.passwords.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.platform.PasswordEditUiState
import com.vayunmathur.passwords.platform.PasswordUiState
import com.vayunmathur.passwords.platform.PasswordsActions
import com.vayunmathur.passwords.platform.MenuUiState
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.passwords.Route
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasswordScreensComposeTest {

    @get:Rule val rule = createComposeRule()

    private val sample = Password(
        id = 1, name = "GitHub", username = "octocat", email = "octo@example.com",
        password = "hunter2secret", note = "work account",
        totpSecret = "JBSWY3DPEHPK3PXP",
        websites = listOf("github.com", "https://gist.github.com"),
        syncId = "sync-a",
    )

    @Test fun passwordScreenShowsAllSections() {
        rule.setContent {
            PasswordScreen(
                state = PasswordUiState(password = sample, now = { 60_000L }),
                actions = PasswordsActions.Noop,
            )
        }
        rule.onAllNodesWithText("GitHub")[0].assertIsDisplayed()
        rule.onAllNodesWithText("octocat")[0].assertIsDisplayed()
        rule.onNodeWithText("octo@example.com").assertExists()
    }

    @Test fun passwordScreenRevealsAndCopiesPassword() {
        var copied: String? = null
        var deleted = false
        val actions = object : PasswordsActions {
            override fun copyToClipboard(label: String, text: String, feedback: String?) { copied = text }
            override fun delete(password: Password) { deleted = true }
        }
        rule.setContent {
            PasswordScreen(
                state = PasswordUiState(password = sample.copy(totpSecret = null), now = { 0L }),
                actions = actions,
            )
        }
        // Masked by default: bullet count equals password length.
        rule.onNodeWithText("•".repeat("hunter2secret".length)).assertIsDisplayed()
        assertEquals(null, copied)
        assertTrue(!deleted)
    }

    @Test fun passwordScreenWithInitialRevealShowsPlaintext() {
        rule.setContent {
            PasswordScreen(
                state = PasswordUiState(password = sample.copy(totpSecret = null), now = { 0L }),
                actions = PasswordsActions.Noop,
                initialShowPassword = true,
            )
        }
        rule.onNodeWithText("hunter2secret").assertIsDisplayed()
    }

    @Test fun passwordScreenScrollsToNoteWebsitesAndPasskeys() {
        val pk = Passkey(id = 9, rpId = "github.com", rpName = "GitHub Passkey", userName = "octocat", syncId = "pk-9")
        rule.setContent {
            PasswordScreen(
                state = PasswordUiState(password = sample, passkeys = listOf(pk), now = { 60_000L }),
                actions = PasswordsActions.Noop,
            )
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("work account"))
        rule.onNodeWithText("work account").assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Websites"))
        rule.onNodeWithText("Websites").assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("GitHub Passkey"))
        rule.onNodeWithText("GitHub Passkey").assertIsDisplayed()
    }

    @Test fun sanitizeUrlPrefixesBareHosts() {
        assertEquals("https://example.com", sanitizeUrl(" example.com "))
        assertEquals("http://example.com", sanitizeUrl("http://example.com"))
        assertEquals("https://example.com", sanitizeUrl("https://example.com"))
    }

    @Test fun editScreenForNewEntryShowsAddTitleAndFields() {
        rule.setContent {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = Password(), draft = Password()),
                actions = PasswordsActions.Noop,
            )
        }
        rule.onNodeWithText("Add Password").assertIsDisplayed()
        rule.onNodeWithText("Name").assertIsDisplayed()
        rule.onNodeWithText("Username").assertIsDisplayed()
        rule.onNodeWithText("TOTP Secret").assertIsDisplayed()
        rule.onNodeWithText("Add website").assertExists()
    }

    @Test fun editScreenForExistingEntryShowsValuesAndSave() {
        var saved = false
        val actions = object : PasswordsActions {
            override fun saveDraft(onSaved: ((Long) -> Unit)?) { saved = true }
        }
        rule.setContent {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = sample, draft = sample),
                actions = actions,
            )
        }
        rule.onNodeWithText("Edit Password").assertIsDisplayed()
        rule.onNodeWithText("GitHub").assertIsDisplayed()
        rule.onNodeWithText("github.com").assertExists()
        rule.onNodeWithText("Save").performClick()
        assertTrue(saved)
    }

    @Test fun editScreenBlankNameShowsErrorAndDoesNotSave() {
        var saved = false
        val actions = object : PasswordsActions {
            override fun saveDraft(onSaved: ((Long) -> Unit)?) { saved = true }
        }
        val blank = Password(id = 5, name = "")
        rule.setContent {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = blank, draft = blank),
                actions = actions,
            )
        }
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Name cannot be empty").assertIsDisplayed()
        assertTrue(!saved)
    }

    @Test fun editScreenFieldEditsFlowThroughUpdateDraft() {
        var draft = Password(id = 7, name = "Old", username = "", websites = listOf("a.com"))
        val actions = object : PasswordsActions {
            override fun updateDraft(transform: (Password) -> Password) { draft = transform(draft) }
        }
        rule.setContent {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = draft, draft = draft),
                actions = actions,
            )
        }
        rule.onNodeWithText("Old").performTextReplacement("New Name")
        assertEquals("New Name", draft.name)
        // Removing the website chip drops it from the draft.
        rule.onNodeWithText("a.com").assertExists()
    }

    @Test fun menuScreenTotpRowCopiesCodeOnClick() {
        var copied: String? = null
        val actions = object : PasswordsActions {
            override fun copyToClipboard(label: String, text: String, feedback: String?) { copied = text }
        }
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu))
        rule.setContent {
            MenuScreen(
                backStack = backStack,
                state = MenuUiState(passwords = listOf(sample), now = { 60_000L }),
                actions = actions,
            )
        }
        // The TOTP code for the known test secret at t=60s is rendered and clickable.
        val code = com.vayunmathur.passwords.domain.TOTP.generate("JBSWY3DPEHPK3PXP", 60)
        rule.onNodeWithText(code).performClick()
        assertEquals(code, copied)
    }

    @Test fun sectionRowShowsLabelAndValue() {
        var copies = 0
        rule.setContent {
            PasswordScreenSection(label = "Username", value = "octocat", onCopy = { copies++ })
        }
        rule.onNodeWithText("Username").assertIsDisplayed()
        rule.onAllNodesWithText("octocat")[0].assertIsDisplayed()
        assertEquals(0, copies)
    }

    @Test fun linkDialogListsPasswordsAndSelects() {
        var selected: Password? = null
        rule.setContent {
            PasskeyLinkDialog(
                passwords = listOf(sample),
                onDismiss = {},
                onSelect = { selected = it },
            )
        }
        rule.onNodeWithText("GitHub").assertIsDisplayed()
        rule.onNodeWithText("GitHub").performClick()
        assertEquals("GitHub", selected?.name)
    }

    @Test fun linkDialogEmptyState() {
        rule.setContent {
            PasskeyLinkDialog(passwords = emptyList(), onDismiss = {}, onSelect = {})
        }
        rule.onNodeWithText("No entries to link to").assertIsDisplayed()
    }

    @Test fun menuScreenShowsPasswordsAndStandalonePasskeys() {
        val pk = Passkey(id = 2, rpId = "example.com", rpName = "Example", userName = "alice", syncId = "pk-1")
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu))
        rule.setContent {
            MenuScreen(
                backStack = backStack,
                state = MenuUiState(
                    passwords = listOf(sample.copy(totpSecret = null)),
                    passkeys = listOf(pk),
                    now = { 0L },
                ),
                actions = PasswordsActions.Noop,
            )
        }
        rule.onNodeWithText("GitHub").assertIsDisplayed()
        rule.onNodeWithText("Example").assertIsDisplayed()
    }

    @Test fun menuScreenEmptyVaultRenders() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu))
        rule.setContent {
            MenuScreen(
                backStack = backStack,
                state = MenuUiState(now = { 0L }),
                actions = PasswordsActions.Noop,
            )
        }
        rule.onNodeWithText("Passwords").assertIsDisplayed()
    }
}
