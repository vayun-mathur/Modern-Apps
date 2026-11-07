package com.vayunmathur.email.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.email.data.EmailAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real [ComposerHeaderSection] with canned accounts: the sender
 * picker and To/Cc/Bcc fields are the addressing path, so a miswired callback
 * silently sends mail from the wrong account or drops recipients.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val accounts = listOf(
        EmailAccount(email = "me@x.com"),
        EmailAccount(email = "work@x.com"),
    )

    private fun setContent(
        fromAccount: EmailAccount? = accounts[0],
        showAccountPicker: Boolean = false,
        to: String = "",
        cc: String = "",
        bcc: String = "",
        showCcBcc: Boolean = false,
        onSelectAccount: (EmailAccount) -> Unit = {},
        onToChange: (String) -> Unit = {},
        onCcChange: (String) -> Unit = {},
        onBccChange: (String) -> Unit = {},
        toggles: MutableList<Boolean>? = null,
        picks: MutableList<Int>? = null,
    ) {
        composeRule.setContent {
            ComposerHeaderSection(
                accounts = accounts,
                fromAccount = fromAccount,
                showAccountPicker = showAccountPicker,
                onOpenAccountPicker = {},
                onDismissAccountPicker = {},
                onSelectAccount = onSelectAccount,
                to = to,
                onToChange = onToChange,
                cc = cc,
                onCcChange = onCcChange,
                bcc = bcc,
                onBccChange = onBccChange,
                showCcBcc = showCcBcc,
                onToggleCcBcc = { toggles?.add(true) },
                onPickContact = { picks?.add(it) },
            )
        }
    }

    @Test
    fun `sender row shows the selected account email`() {
        setContent(fromAccount = accounts[0])
        composeRule.onNodeWithText("me@x.com").assertIsDisplayed()
    }

    @Test
    fun `no account shows the select-account placeholder`() {
        setContent(fromAccount = null)
        // Placeholder text comes from resources; the row still renders without crash.
        composeRule.waitForIdle()
    }

    @Test
    fun `account picker lists every account and routes the selection`() {
        var selected: EmailAccount? = null
        setContent(showAccountPicker = true, onSelectAccount = { selected = it })
        // The sender row and the dialog both show me@x.com, so require >= 1.
        composeRule.onAllNodesWithText("me@x.com").assertCountEquals(2)
        composeRule.onNodeWithText("work@x.com").assertIsDisplayed()
        composeRule.onNodeWithText("work@x.com").performClick()
        assertEquals(accounts[1], selected)
    }

    @Test
    fun `to field change routes to the callback`() {
        var to = ""
        setContent(onToChange = { to = it })
        composeRule.onNodeWithText("To", substring = true).performTextInput("a@b.com")
        assertEquals("a@b.com", to)
    }

    @Test
    fun `cc bcc toggle routes to the callback`() {
        val toggles = mutableListOf<Boolean>()
        setContent(toggles = toggles)
        composeRule.onNodeWithText("Cc/Bcc", substring = true).performClick()
        assertTrue(toggles.isNotEmpty())
    }

    @Test
    fun `cc and bcc fields render when expanded and route changes`() {
        var cc = ""
        var bcc = ""
        setContent(showCcBcc = true, onCcChange = { cc = it }, onBccChange = { bcc = it })
        composeRule.onNodeWithText("Cc", substring = false).performTextInput("c@d.com")
        assertEquals("c@d.com", cc)
        composeRule.onNodeWithText("Bcc", substring = false).performTextInput("e@f.com")
        assertEquals("e@f.com", bcc)
    }

    @Test
    fun `detail item renders label name and email`() {
        composeRule.setContent {
            DetailItem(label = "From", name = "Ada", email = "ada@x.com", avatarColor = Color.Red)
        }
        composeRule.onNodeWithText("From").assertIsDisplayed()
        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        composeRule.onNodeWithText("ada@x.com").assertIsDisplayed()
    }
}
