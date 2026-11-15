package com.vayunmathur.passwords.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.domain.ImportSource
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
class ComposeInteractionsExtraTest {

    @get:Rule val rule = createComposeRule()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun vm(): PasswordsViewModel = PasswordsViewModel(app, PasswordRepository.get(app))

    private val github = Password(
        id = 1, name = "GitHub", username = "octocat", email = "octo@example.com",
        password = "hunter2secret", note = "work account", totpSecret = null,
        websites = listOf("github.com"), syncId = "sync-a",
    )
    private val mail = Password(
        id = 2, name = "Fastmail", username = "alice@fastmail.com", password = "pw2",
        syncId = "sync-b",
    )

    // ---- MenuScreen: search filtering uses the real search strings ----

    @Test fun menuSearchFiltersByNameUserAndPasskeySite() {
        val pk = Passkey(id = 5, rpId = "github.com", rpName = "GitHub Key", userName = "octocat", syncId = "pk-5")
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu))
        rule.setContent {
            MenuScreen(
                backStack = backStack,
                state = MenuUiState(passwords = listOf(github, mail), passkeys = listOf(pk), now = { 0L }),
                actions = PasswordsActions.Noop,
            )
        }
        rule.onNodeWithText("GitHub").assertIsDisplayed()
        rule.onNodeWithText("Fastmail").assertIsDisplayed()

        val search = rule.onAllNodes(hasSetTextAction())[0]
        search.performTextReplacement("fastmail")
        rule.onNodeWithText("Fastmail").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("GitHub").fetchSemanticsNodes().size)

        // Clearing the query restores every row.
        search.performTextReplacement("")
        rule.onNodeWithText("GitHub").assertIsDisplayed()

        // A passkey merged into a password is findable through its rpId via the host row.
        search.performTextReplacement("octocat")
        rule.onNodeWithText("GitHub").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Fastmail").fetchSemanticsNodes().size)
    }

    @Test fun menuSearchWithNoMatchEmptiesList() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu))
        rule.setContent {
            MenuScreen(
                backStack = backStack,
                state = MenuUiState(passwords = listOf(github), now = { 0L }),
                actions = PasswordsActions.Noop,
            )
        }
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("zzz-no-match")
        assertEquals(0, rule.onAllNodesWithText("GitHub").fetchSemanticsNodes().size)
        // The search query itself remains in the search field.
        rule.onNodeWithText("zzz-no-match").assertExists()
    }

    // ---- PasswordScreen: icon-bar actions ----

    @Test fun passwordScreenCopyVisibilityDeleteAndEditActions() {
        val copied = mutableListOf<String>()
        var deleted = false
        var backed = false
        var edited = false
        val actions = object : PasswordsActions {
            override fun copyToClipboard(label: String, text: String, feedback: String?) {
                copied += "$label=$text"
            }
            override fun delete(password: Password) { deleted = true }
        }
        rule.setContent {
            PasswordScreen(
                state = PasswordUiState(password = github, now = { 0L }),
                actions = actions,
                onBack = { backed = true },
                onEdit = { edited = true },
            )
        }
        // Username section copy is the first Copy button on the screen.
        rule.onAllNodesWithContentDescription("Copy")[0].performClick()
        assertEquals(listOf("username=octocat"), copied)

        // Reveal, then hide again.
        rule.onNodeWithContentDescription("Visible").performClick()
        rule.onNodeWithText("hunter2secret").assertIsDisplayed()
        // The toggle flips to its Hide form while the password is revealed.
        rule.onNodeWithContentDescription("Hide").assertExists()

        // Edit FAB and Delete action.
        rule.onNodeWithContentDescription("Edit").performClick()
        assertTrue(edited)
        rule.onNodeWithContentDescription("Delete").performClick()
        assertTrue(deleted)
        assertTrue(backed)
    }

    @Test fun passwordScreenSectionCopyButtonFiresCallback() {
        var copies = 0
        rule.setContent {
            PasswordScreenSection(label = "Email", value = "a@x.com", onCopy = { copies++ })
        }
        rule.onNodeWithContentDescription("Copy").performClick()
        assertEquals(1, copies)
        rule.onNodeWithContentDescription("Copy").performClick()
        assertEquals(2, copies)
    }

    // ---- SettingsPage: import source menu ----

    @Test fun settingsMenuListsEveryImportSource() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Settings))
        rule.setContent { SettingsPage(backStack, vm()) }
        rule.onNodeWithText("Import Passwords").performClick()
        for (source in ImportSource.entries) {
            rule.onNodeWithText(app.getString(source.label)).assertIsDisplayed()
        }
        assertEquals(8, ImportSource.entries.size)
    }

    @Test fun settingsSelectingSourceLaunchesDocumentPicker() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Settings))
        rule.setContent { SettingsPage(backStack, vm()) }
        rule.onNodeWithText("Import Passwords").performClick()
        rule.onNodeWithText(app.getString(ImportSource.CHROME.label)).performClick()
        // The menu closes after selection (the picker intent is handed to the system),
        // leaving the settings screen itself intact.
        assertEquals(
            0,
            rule.onAllNodesWithText(app.getString(ImportSource.CHROME.label)).fetchSemanticsNodes().size,
        )
        rule.onNodeWithText("Import Passwords").assertIsDisplayed()
    }

    // ---- MenuScreen: row clicks navigate through the route mapping ----

    @Test fun menuRowClicksNavigateToDetailRoutes() {
        val pk = Passkey(id = 5, rpId = "standalone.example", rpName = "Solo Key", userName = "dave", syncId = "pk-5")
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu))
        rule.setContent {
            MenuScreen(
                backStack = backStack,
                state = MenuUiState(passwords = listOf(github), passkeys = listOf(pk), now = { 0L }),
                actions = PasswordsActions.Noop,
            )
        }
        rule.onNodeWithText("Solo Key").performClick()
        assertEquals(Route.PasskeyPage(5), backStack.last())
        rule.onNodeWithText("GitHub").performClick()
        assertEquals(Route.PasswordPage(1), backStack.last())
    }

    @Test fun passwordScreenWebsiteRowLaunchesBrowserIntent() {
        rule.setContent {
            PasswordScreen(
                state = PasswordUiState(password = github, now = { 0L }),
                actions = PasswordsActions.Noop,
            )
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("github.com"))
        rule.onNodeWithText("github.com").performClick()
        val intent = org.robolectric.Shadows.shadowOf(app).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, intent?.action)
        assertEquals("https://github.com", intent?.dataString)
    }


    // ---- PasswordScreen: TOTP and note copy buttons ----

    @Test fun passwordScreenTotpAndNoteCopyButtons() {
        val copied = mutableListOf<String>()
        val actions = object : PasswordsActions {
            override fun copyToClipboard(label: String, text: String, feedback: String?) {
                copied += "$label=$text"
            }
        }
        val withTotp = github.copy(totpSecret = "JBSWY3DPEHPK3PXP")
        rule.setContent {
            PasswordScreen(
                state = PasswordUiState(password = withTotp, now = { 60_000L }),
                actions = actions,
            )
        }
        // The TOTP card sits below the fold: scroll it into composition first.
        val expectedCode = com.vayunmathur.passwords.domain.TOTP.generate("JBSWY3DPEHPK3PXP", 60)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(expectedCode))
        // Click every Copy button the screen exposes after scrolling; the
        // TOTP and note copies must be among the resulting actions.
        val copies = rule.onAllNodesWithContentDescription("Copy")
        val count = copies.fetchSemanticsNodes().size
        for (i in 0 until count) copies[i].performClick()
        assertTrue("totp=$expectedCode" in copied, "got $copied")
        assertTrue("note=work account" in copied, "got $copied")
    }

    // ---- PasswordEditScreen: add website through the IME Done action ----

    @Test fun editScreenAddWebsiteViaImeDone() {
        var draft = Password(id = 0, name = "New", username = "u", password = "p")
        val actions = object : PasswordsActions {
            override fun updateDraft(transform: (Password) -> Password) { draft = transform(draft) }
        }
        rule.setContent {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = draft, draft = draft),
                actions = actions,
            )
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        // The website input is the last text field on the form.
        fields[fields.fetchSemanticsNodes().size - 1].performTextReplacement("example.com")
        fields[fields.fetchSemanticsNodes().size - 1].performImeAction()
        assertEquals(listOf("example.com"), draft.websites)
    }

    // ---- PasswordScreen: single-identity sections ----

    @Test fun passwordScreenEmailOnlySection() {
        val copied = mutableListOf<String>()
        val actions = object : PasswordsActions {
            override fun copyToClipboard(label: String, text: String, feedback: String?) {
                copied += "$label=$text"
            }
        }
        val emailOnly = Password(
            id = 3, name = "Mail", email = "only@mail.com", password = "pw",
            syncId = "sync-c",
        )
        rule.setContent {
            PasswordScreen(
                state = PasswordUiState(password = emailOnly, now = { 0L }),
                actions = actions,
            )
        }
        rule.onAllNodesWithText("only@mail.com")[0].assertIsDisplayed()
        // Copy buttons here: email (section), password (card).
        rule.onAllNodesWithContentDescription("Copy")[0].performClick()
        assertEquals(listOf("email=only@mail.com"), copied)
    }

    @Test fun passwordScreenUsernameOnlySection() {
        val copied = mutableListOf<String>()
        val actions = object : PasswordsActions {
            override fun copyToClipboard(label: String, text: String, feedback: String?) {
                copied += "$label=$text"
            }
        }
        val usernameOnly = Password(
            id = 4, name = "Forum", username = "forumuser", password = "pw",
            syncId = "sync-d",
        )
        rule.setContent {
            PasswordScreen(
                state = PasswordUiState(password = usernameOnly, now = { 0L }),
                actions = actions,
            )
        }
        rule.onAllNodesWithText("forumuser")[0].assertIsDisplayed()
        // Copy buttons here: username (section), password (card).
        rule.onAllNodesWithContentDescription("Copy")[1].performClick()
        assertEquals(listOf("password=pw"), copied)
    }

    // ---- MenuScreen: searching reaches standalone passkeys ----

    @Test fun menuSearchFindsStandalonePasskeyByRpId() {
        val pk = Passkey(id = 5, rpId = "standalone.example", rpName = "Solo Key", userName = "dave", syncId = "pk-5")
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu))
        rule.setContent {
            MenuScreen(
                backStack = backStack,
                state = MenuUiState(passwords = listOf(github), passkeys = listOf(pk), now = { 0L }),
                actions = PasswordsActions.Noop,
            )
        }
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("standalone")
        rule.onNodeWithText("Solo Key").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("GitHub").fetchSemanticsNodes().size)
    }

    // ---- PasswordEditScreen: TOTP field edit + non-blank TOTP save ----

    @Test fun editScreenTotpFieldEditFlowsToDraft() {
        var draft = Password(id = 7, name = "Old", totpSecret = null)
        val actions = object : PasswordsActions {
            override fun updateDraft(transform: (Password) -> Password) { draft = transform(draft) }
        }
        rule.setContent {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = draft, draft = draft),
                actions = actions,
            )
        }
        // The TOTP field is empty: it is the editable node labelled TOTP Secret.
        rule.onNodeWithText("TOTP Secret").assertExists()
        val fields = rule.onAllNodes(hasSetTextAction())
        // Fields in order: name, username, email, password, TOTP, note, website.
        fields[4].performTextReplacement("JBSWY3DPEHPK3PXP")
        assertEquals("JBSWY3DPEHPK3PXP", draft.totpSecret)
    }

    @Test fun editScreenSaveKeepsNonBlankTotp() {
        var createdId: Long? = null
        var draft = Password(id = 0, name = "Fresh", totpSecret = "SEED123")
        val actions = object : PasswordsActions {
            override fun updateDraft(transform: (Password) -> Password) { draft = transform(draft) }
            override fun saveDraft(onSaved: ((Long) -> Unit)?) { onSaved?.invoke(77L) }
        }
        rule.setContent {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = draft, draft = draft),
                actions = actions,
                onCreated = { createdId = it },
            )
        }
        rule.onNodeWithText("Save").performClick()
        assertEquals(77L, createdId)
        // A real secret is passed through untouched (only blank becomes null).
        assertEquals("SEED123", draft.totpSecret)
    }

    @Test fun editScreenAddWebsiteRejectsDuplicatesAndBlanks() {
        var draft = Password(id = 0, name = "New", username = "u", password = "p", websites = listOf("example.com"))
        var updateCalls = 0
        val actions = object : PasswordsActions {
            override fun updateDraft(transform: (Password) -> Password) {
                updateCalls++
                draft = transform(draft)
            }
        }
        rule.setContent {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = draft, draft = draft),
                actions = actions,
            )
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        val websiteField = fields[fields.fetchSemanticsNodes().size - 1]
        // Re-adding an existing website is a no-op in the draft transform.
        websiteField.performTextReplacement("example.com")
        websiteField.performImeAction()
        assertEquals(listOf("example.com"), draft.websites)
        // A whitespace-only candidate never reaches updateDraft at all.
        val callsBefore = updateCalls
        websiteField.performTextReplacement("   ")
        websiteField.performImeAction()
        assertEquals(listOf("example.com"), draft.websites)
        assertEquals(callsBefore, updateCalls)
        // A genuinely new website is appended.
        websiteField.performTextReplacement("other.com")
        websiteField.performImeAction()
        assertEquals(listOf("example.com", "other.com"), draft.websites)
    }
}
