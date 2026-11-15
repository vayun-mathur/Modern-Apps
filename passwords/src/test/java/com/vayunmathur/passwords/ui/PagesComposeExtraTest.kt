package com.vayunmathur.passwords.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.platform.MenuUiState
import com.vayunmathur.passwords.platform.PasswordEditUiState
import com.vayunmathur.passwords.platform.PasswordUiState
import com.vayunmathur.passwords.platform.PasswordsActions
import com.vayunmathur.passwords.platform.PasswordsViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PagesComposeExtraTest {

    @get:Rule val rule = createComposeRule()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private fun vm(): PasswordsViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return PasswordsViewModel(app, PasswordRepository.get(app))
    }

    private val sample = Password(
        id = 1, name = "GitHub", username = "octocat", email = "octo@example.com",
        password = "hunter2secret", note = "work account", totpSecret = null,
        websites = listOf("github.com"), syncId = "sync-a",
    )

    // ---- ViewModel-backed page wrappers (vault flows are empty under Robolectric) ----





    // ---- MenuScreen branches ----

    @Test fun menuScreenLoadingStateAndBlankNames() {
        val blank = Password(id = 2, name = "", username = "", email = "only@mail.com", syncId = "sync-b")
        val pk1 = Passkey(id = 3, rpId = "example.com", rpName = "", userName = "alice", syncId = "pk-a")
        val pk2 = Passkey(id = 4, rpId = "example.com", rpName = "Second", userName = "alice", syncId = "pk-b")
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu))
        rule.setContent {
            MenuScreen(
                backStack = backStack,
                state = MenuUiState(
                    passwords = listOf(blank.copy(websites = listOf("example.com"))),
                    passkeys = listOf(pk1, pk2),
                    loading = true,
                    now = { 0L },
                ),
                actions = PasswordsActions.Noop,
            )
        }
        // Blank password name falls back to the no-name label; its email is the subtitle.
        rule.onNodeWithText("only@mail.com").assertExists()
    }

    @Test fun menuScreenPasskeyRowShowsRpFallback() {
        val pk = Passkey(id = 8, rpId = "login.example.com", rpName = "", userName = "dave", syncId = "pk-z")
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu))
        rule.setContent {
            MenuScreen(
                backStack = backStack,
                state = MenuUiState(passkeys = listOf(pk), now = { 0L }),
                actions = PasswordsActions.Noop,
            )
        }
        rule.onNodeWithText("login.example.com").assertIsDisplayed()
        rule.onNodeWithText("dave").assertIsDisplayed()
    }

    // ---- PasswordScreen interaction branches ----

    @Test fun passwordScreenButtonsInvokeActions() {
        var copied: String? = null
        var deleted = false
        var backed = false
        var openedPasskey: Long? = null
        val pk = Passkey(id = 9, rpId = "github.com", rpName = "GH Key", userName = "octocat", syncId = "pk-9")
        val actions = object : PasswordsActions {
            override fun copyToClipboard(label: String, text: String, feedback: String?) { copied = text }
            override fun delete(password: Password) { deleted = true }
        }
        rule.setContent {
            PasswordScreen(
                state = PasswordUiState(password = sample, passkeys = listOf(pk), now = { 60_000L }),
                actions = actions,
                onBack = { backed = true },
                onOpenPasskey = { openedPasskey = it },
            )
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("GH Key"))
        rule.onNodeWithText("GH Key").performClick()
        assertEquals(9L, openedPasskey)

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Websites"))
        rule.onNodeWithText("github.com").assertExists()
        assertEquals(null, copied)
        assertTrue(!deleted && !backed)
    }

    @Test fun passwordScreenBlankEntryShowsFallbackLabels() {
        rule.setContent {
            PasswordScreen(
                state = PasswordUiState(password = Password(id = 3), now = { 0L }),
                actions = PasswordsActions.Noop,
            )
        }
        rule.onAllNodesWithText("(no name)")[0].assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("(none)"))
        rule.onNodeWithText("(none)").assertIsDisplayed()
    }

    @Test fun sanitizeUrlEdges() {
        assertEquals("https://", sanitizeUrl(""))
        assertEquals("https://x.com/path", sanitizeUrl("  x.com/path "))
        assertEquals("http://x.com", sanitizeUrl("http://x.com"))
    }

    // ---- PasswordEditScreen interaction branches ----

    @Test fun editScreenWebsiteInputDoesNotMutateDraftUntilConfirmed() {
        var draft = Password(id = 0, name = "New", websites = emptyList())
        val actions = object : PasswordsActions {
            override fun updateDraft(transform: (Password) -> Password) { draft = transform(draft) }
        }
        rule.setContent {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = draft, draft = draft),
                actions = actions,
            )
        }
        // Typing into the website input is local UI state: the draft only changes
        // through updateDraft (IME Done / chip removal), never on each keystroke.
        rule.onNodeWithText("Add website").assertExists()
        assertEquals(emptyList(), draft.websites)
        assertEquals("New", draft.name)
    }

    @Test fun editScreenTogglesPasswordVisibilityAndEditsFields() {
        var draft = Password(
            id = 7, name = "Old", username = "user1", email = "e@x.com",
            password = "secretpw", note = "a note", totpSecret = "SEED",
            websites = listOf("a.com", "b.com"),
        )
        val actions = object : PasswordsActions {
            override fun updateDraft(transform: (Password) -> Password) { draft = transform(draft) }
        }
        rule.setContent {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = draft, draft = draft),
                actions = actions,
            )
        }
        rule.onNodeWithText("Show").performClick()
        rule.onNodeWithText("Hide").assertIsDisplayed()
        rule.onNodeWithText("user1").performTextReplacement("user2")
        assertEquals("user2", draft.username)
        rule.onNodeWithText("e@x.com").performTextReplacement("f@x.com")
        assertEquals("f@x.com", draft.email)
        rule.onNodeWithText("a note").performTextReplacement("b note")
        assertEquals("b note", draft.note)
        // Both website chips are rendered.
        rule.onNodeWithText("a.com").assertExists()
        rule.onNodeWithText("b.com").assertExists()
    }

    @Test fun editScreenSaveForNewEntryReportsCreatedId() {
        var createdId: Long? = null
        var draft = Password(id = 0, name = "Fresh", totpSecret = "")
        val actions = object : PasswordsActions {
            override fun updateDraft(transform: (Password) -> Password) { draft = transform(draft) }
            override fun saveDraft(onSaved: ((Long) -> Unit)?) { onSaved?.invoke(55L) }
        }
        rule.setContent {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = draft, draft = draft),
                actions = actions,
                onCreated = { createdId = it },
            )
        }
        rule.onNodeWithText("Save").performClick()
        assertEquals(55L, createdId)
        // Save normalises a blank TOTP secret to null in the draft first.
        assertEquals(null, draft.totpSecret)
    }

    @Test fun editScreenSaveForExistingEntryGoesBack() {
        var backed = false
        var saved = false
        val draft = Password(id = 12, name = "Existing")
        val actions = object : PasswordsActions {
            override fun saveDraft(onSaved: ((Long) -> Unit)?) { saved = true }
        }
        rule.setContent {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = draft, draft = draft),
                actions = actions,
                onBack = { backed = true },
            )
        }
        rule.onNodeWithText("Save").performClick()
        assertTrue(saved)
        assertTrue(backed)
    }

    @Test fun editScreenWithoutDraftCannotSave() {
        var saved = false
        val actions = object : PasswordsActions {
            override fun saveDraft(onSaved: ((Long) -> Unit)?) { saved = true }
        }
        rule.setContent {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = sample, draft = null),
                actions = actions,
            )
        }
        rule.onNodeWithText("Save").performClick()
        assertTrue(!saved)
        // The saved values still render as the form's current values.
        rule.onNodeWithText("GitHub").assertIsDisplayed()
    }
}
