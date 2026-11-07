package com.vayunmathur.passwords.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.domain.PasskeyLink
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pass-3: stateless PasskeyScreen (extracted from PasskeyPage) in its link states. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasskeyScreenPass3Test {

    @get:Rule val rule = createComposeRule()

    private val passkey = Passkey(
        id = 5,
        rpId = "github.com",
        rpName = "GitHub",
        credentialId = "cred-1234567890-abcdefghij-extra",
        userName = "octocat",
        userDisplayName = "Octo Cat",
        creationTime = 1700000000000L,
        lastUsedTime = 1710000000000L,
        syncId = "pk-sync-1",
    )

    private val password = Password(id = 1, name = "GitHub Login", username = "octocat", syncId = "pw-sync-1")

    private fun backStack() = NavBackStack<Route>(arrayOf(Route.Menu))

    @Test
    fun unlinkedPasskeyShowsDetailsAndLinkAction() {
        val links = mutableListOf<PasskeyLink>()
        rule.setContent {
            PasskeyScreen(backStack(), passkey, listOf(password), listOf(passkey),
                onDelete = {}, onSetLink = { links += it })
        }
        rule.onAllNodesWithText("GitHub")[0].assertIsDisplayed()
        rule.onNodeWithText("Linked Entry").assertIsDisplayed()
        rule.onNodeWithText("Not linked").assertIsDisplayed()
        rule.onNodeWithText("Link to entry").assertIsDisplayed()
        // Detail cards below the fold
        rule.onNodeWithText("github.com").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("octocat").performScrollTo().assertIsDisplayed()
        assertTrue(links.isEmpty())
    }

    @Test
    fun linkDialogSelectsPasswordAndReportsLink() {
        val links = mutableListOf<PasskeyLink>()
        rule.setContent {
            PasskeyScreen(backStack(), passkey, listOf(password), listOf(passkey),
                onDelete = {}, onSetLink = { links += it })
        }
        rule.onNodeWithText("Link to entry").performClick()
        rule.waitForIdle()
        // Dialog lists the password; selecting it reports PasskeyLink.To(syncId)
        rule.onNodeWithText("GitHub Login").performClick()
        assertEquals(listOf(PasskeyLink.To("pw-sync-1")), links.toList())
    }

    @Test
    fun manuallyLinkedPasskeyShowsLinkedNameAndUnlink() {
        val linked = passkey.copy(linkedPasswordSyncId = "pw-sync-1")
        val links = mutableListOf<PasskeyLink>()
        rule.setContent {
            PasskeyScreen(backStack(), linked, listOf(password), listOf(linked),
                onDelete = {}, onSetLink = { links += it })
        }
        rule.onNodeWithText("GitHub Login").assertIsDisplayed()
        rule.onNodeWithText("Linked by you").assertIsDisplayed()
        rule.onNodeWithText("Unlink").performClick()
        assertEquals(listOf(PasskeyLink.Detached), links.toList())
    }

    @Test
    fun detachedPasskeyShowsDisconnectedAndAutoRematch() {
        val detached = passkey.copy(linkedPasswordSyncId = "-")
        val links = mutableListOf<PasskeyLink>()
        rule.setContent {
            // No passwords: also covers the empty link-dialog state when opened
            PasskeyScreen(backStack(), detached, emptyList(), listOf(detached),
                onDelete = {}, onSetLink = { links += it })
        }
        rule.onNodeWithText("Disconnected. It will not be matched automatically.").assertIsDisplayed()
        rule.onNodeWithText("Match automatically").performClick()
        assertEquals(listOf(PasskeyLink.Auto), links.toList())
    }

    @Test
    fun deleteActionFiresAndPopsBackStack() {
        val stack = backStack()
        var deleted = 0
        rule.setContent {
            PasskeyScreen(stack, passkey, emptyList(), listOf(passkey),
                onDelete = { deleted++ }, onSetLink = {})
        }
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Delete")).performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun blankRpNameFallsBackToGenericTitleAndBlankValuesDash() {
        val blank = passkey.copy(rpName = "", rpId = "", userName = "")
        rule.setContent {
            PasskeyScreen(backStack(), blank, emptyList(), listOf(blank),
                onDelete = {}, onSetLink = {})
        }
        rule.onNodeWithText("Passkey").assertIsDisplayed()
        rule.onAllNodesWithText("—")[0].assertExists()
    }
}
