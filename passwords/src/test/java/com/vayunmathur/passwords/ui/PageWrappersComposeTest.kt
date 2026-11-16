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
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.Navigation
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.data.PASSKEY_LINK_DETACHED
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.platform.PasswordsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ViewModel-backed page wrappers ([PasskeyPage], [PasswordPage],
 * [PasswordEditPage], [MenuPage], [Navigation]).
 *
 * The vault itself cannot be opened under Robolectric (SQLCipher's native
 * library is unavailable there), so the ViewModel's cold Room flows never
 * emit. These tests seed the value held inside each `stateIn` StateFlow
 * directly — the composables under test, their state resolution
 * (`passwordState`, `mergeCredentials`, draft seeding) and their rendering
 * all run for real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageWrappersComposeTest {

    @get:Rule val rule = createComposeRule()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun vm(
        passwords: List<Password> = emptyList(),
        passkeys: List<Passkey> = emptyList(),
        loaded: Boolean = true,
    ): PasswordsViewModel {
        val viewModel = PasswordsViewModel(app, PasswordRepository.get(app))
        // Replace the Room-backed stateIn flows with plain MutableStateFlows:
        // collecting the real ones would open SQLCipher (native library
        // unavailable under Robolectric) inside viewModelScope and the Error
        // would escape the sharing coroutine and fail the test. The pages,
        // their derived state and rendering all run against these flows
        // exactly as they would against the database-backed ones.
        replaceFlow(viewModel, "passwords", MutableStateFlow(passwords))
        replaceFlow(viewModel, "passkeys", MutableStateFlow(passkeys))
        replaceFlow(viewModel, "tickerFlow", MutableStateFlow(60_000L))
        if (loaded) setLoaded(viewModel)
        return viewModel
    }

    private fun replaceFlow(viewModel: PasswordsViewModel, name: String, flow: MutableStateFlow<*>) {
        val field = PasswordsViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(viewModel, flow)
    }

    private fun setLoaded(viewModel: PasswordsViewModel) {
        val field = PasswordsViewModel::class.java.getDeclaredField("_loaded")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(viewModel) as MutableStateFlow<Boolean>).value = true
    }

    private val github = Password(
        id = 1, name = "GitHub", username = "octocat", email = "octo@example.com",
        password = "hunter2secret", note = "work account",
        websites = listOf("github.com"), syncId = "sync-a",
    )
    private val passkey = Passkey(
        id = 7, rpId = "github.com", rpName = "GitHub Key", credentialId = "cred-1234567890-abcdefghij",
        userId = "uid-1", userName = "octocat", userDisplayName = "Octo Cat",
        creationTime = 1_700_000_000_000, lastUsedTime = 1_700_100_000_000,
        signCount = 3, syncId = "pk-7",
    )

    // ---- PasskeyPage ----

    @Test fun passkeyPageAutoLinkedShowsLinkedPasswordAndDetails() {
        val viewModel = vm(passwords = listOf(github), passkeys = listOf(passkey))
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu, Route.PasskeyPage(7)))
        rule.setContent { PasskeyPage(backStack, 7, viewModel) }

        // Title comes from rpName; the auto-matched password is the link target.
        rule.onAllNodesWithText("GitHub Key")[0].assertIsDisplayed()
        rule.onNodeWithText("GitHub").assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.passkey_link_auto)).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.passkey_disconnect)).assertIsDisplayed()
        // Detail cards below the link section.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("github.com"))
        rule.onNodeWithText("github.com").assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Octo Cat"))
        rule.onNodeWithText("Octo Cat").assertExists()
        // A credential id longer than 20 chars is truncated with an ellipsis.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("cred-1234567890-abcd…"))
        rule.onNodeWithText("cred-1234567890-abcd…").assertExists()
    }

    @Test fun passkeyPageDetachedShowsDetachedTextAndAutoOption() {
        val detached = passkey.copy(
            rpName = "", userName = "", userDisplayName = "",
            linkedPasswordSyncId = PASSKEY_LINK_DETACHED,
        )
        val viewModel = vm(passwords = listOf(github), passkeys = listOf(detached))
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu, Route.PasskeyPage(7)))
        rule.setContent { PasskeyPage(backStack, 7, viewModel) }

        // Blank rpName falls back to the generic title in the app bar.
        rule.onNodeWithText(app.getString(R.string.passkey_detail_title)).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.passkey_link_detached)).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.passkey_link_use_auto)).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.passkey_link_dialog_title)).assertIsDisplayed()
        // Blank detail values render as an em dash.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("—"))
        assertTrue(rule.onAllNodesWithText("—").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun passkeyPageManualLinkShowsManualLabel() {
        val manual = passkey.copy(linkedPasswordSyncId = "sync-a")
        val viewModel = vm(passwords = listOf(github), passkeys = listOf(manual))
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu, Route.PasskeyPage(7)))
        rule.setContent { PasskeyPage(backStack, 7, viewModel) }
        rule.onNodeWithText(app.getString(R.string.passkey_link_manual)).assertIsDisplayed()
    }

    @Test fun passkeyPageUnlinkedShowsNoneAndLinkAffordance() {
        val viewModel = vm(passwords = emptyList(), passkeys = listOf(passkey))
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu, Route.PasskeyPage(7)))
        rule.setContent { PasskeyPage(backStack, 7, viewModel) }
        rule.onNodeWithText(app.getString(R.string.passkey_link_none)).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.passkey_link_dialog_title)).assertIsDisplayed()
    }

    @Test fun passkeyPageLinkPickerListsPasswords() {
        val viewModel = vm(passwords = listOf(github), passkeys = listOf(passkey.copy(userName = "nobody")))
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu, Route.PasskeyPage(7)))
        rule.setContent { PasskeyPage(backStack, 7, viewModel) }
        // "nobody" does not auto-match GitHub's octocat, so the picker can open.
        rule.onNodeWithText(app.getString(R.string.passkey_link_dialog_title)).performClick()
        rule.onNodeWithText("GitHub").assertIsDisplayed()
    }

    @Test fun passkeyPageForUnknownIdRendersNothing() {
        val viewModel = vm(passkeys = listOf(passkey))
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu, Route.PasskeyPage(99)))
        rule.setContent { PasskeyPage(backStack, 99, viewModel) }
        assertEquals(
            0,
            rule.onAllNodesWithText("GitHub Key").fetchSemanticsNodes().size,
        )
    }

    // ---- PasswordPage wrapper ----

    @Test fun passwordPageWrapperRendersResolvedPasswordAndPasskeys() {
        val viewModel = vm(passwords = listOf(github), passkeys = listOf(passkey))
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu, Route.PasswordPage(1)))
        rule.setContent { PasswordPage(backStack, 1, viewModel) }
        rule.onAllNodesWithText("GitHub")[0].assertIsDisplayed()
        rule.onAllNodesWithText("octocat")[0].assertIsDisplayed()
        // The passkey merged into this password is listed on its detail page.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("GitHub Key"))
        rule.onNodeWithText("GitHub Key").assertExists()
    }

    @Test fun passwordPageWrapperForUnknownIdRendersDefaultEntry() {
        val viewModel = vm(passwords = listOf(github))
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu, Route.PasswordPage(42)))
        rule.setContent { PasswordPage(backStack, 42, viewModel) }
        // passwordState falls back to a blank Password() for an absent id.
        rule.onNodeWithText(app.getString(R.string.no_name)).assertIsDisplayed()
    }

    // ---- PasswordEditPage wrapper ----

    @Test fun passwordEditPageWrapperSeedsDraftFromStoredPassword() {
        val viewModel = vm(passwords = listOf(github))
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu, Route.PasswordEditPage(1)))
        rule.setContent { PasswordEditPage(backStack, 1, viewModel) }
        rule.onNodeWithText("GitHub").assertIsDisplayed()
        // LaunchedEffect(id, pass) seeds the draft exactly once from the row.
        rule.waitUntil(timeoutMillis = 5_000) { viewModel.draft.value?.id == 1L }
        assertEquals("GitHub", viewModel.draft.value?.name)
        assertEquals(listOf("github.com"), viewModel.draft.value?.websites)
    }

    @Test fun passwordEditPageWrapperForNewEntrySeedsBlankDraft() {
        val viewModel = vm(passwords = emptyList())
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu, Route.PasswordEditPage(0)))
        rule.setContent { PasswordEditPage(backStack, 0, viewModel) }
        rule.waitUntil(timeoutMillis = 5_000) { viewModel.draft.value != null }
        assertEquals(0L, viewModel.draft.value?.id)
        assertEquals("", viewModel.draft.value?.name)
    }

    // ---- MenuPage wrapper ----

    @Test fun menuPageWrapperRendersVaultRows() {
        val viewModel = vm(passwords = listOf(github), passkeys = listOf(passkey))
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu))
        rule.setContent { MenuPage(backStack, viewModel) }
        rule.onNodeWithText("GitHub").assertIsDisplayed()
        // The passkey is merged into GitHub (not a standalone row).
        assertEquals(0, rule.onAllNodesWithText("GitHub Key").fetchSemanticsNodes().size)
    }

    @Test fun menuPageWrapperStandalonePasskeyRowNavigates() {
        val solo = passkey.copy(id = 8, rpId = "solo.example", rpName = "Solo Key", userName = "dave")
        val viewModel = vm(passkeys = listOf(solo))
        val backStack = NavBackStack<Route>(arrayOf(Route.Menu))
        rule.setContent { MenuPage(backStack, viewModel) }
        rule.onNodeWithText("Solo Key").assertIsDisplayed()
        rule.onNodeWithText("Solo Key").performClick()
        assertEquals(Route.PasskeyPage(8), backStack.last())
    }

    // ---- Navigation ----

    @Test fun navigationStartsOnMenuWithVaultRows() {
        val viewModel = vm(passwords = listOf(github), passkeys = listOf(passkey))
        rule.setContent { Navigation(viewModel) }
        rule.onNodeWithText("GitHub").assertIsDisplayed()
        rule.onAllNodesWithText("octocat")[0].assertIsDisplayed()
    }
}
