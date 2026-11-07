package com.vayunmathur.communicate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.R
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UiPass3 coverage for account + registration screens: [AccountsScreen]
 * signed-out state, and the first step of the WhatsApp/Signal registration
 * flows (typing a number must never touch the network — buttons are only
 * asserted enabled/disabled, never pressed past the request step).
 */
@RunWith(RobolectricTestRunner::class)
class AccountsUiPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int): String = context.getString(id)

    @Test
    fun `accounts shows sim and signed-out google voice`() {
        var signIns = 0
        rule.setContent {
            AccountsScreen(onBack = {}, onSignIn = { signIns++ })
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.accounts_title)).assertIsDisplayed()
        rule.onAllNodesWithText(str(R.string.account_sim)).fetchSemanticsNodes()
            .let { assertEquals(true, it.isNotEmpty()) }
        rule.onNodeWithText(str(R.string.account_google_voice)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.gv_not_signed_in)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.gv_sign_in)).performClick()
        rule.waitForIdle()
        assertEquals(1, signIns)
    }

    @Test
    fun `accounts offers whatsapp registration when signed out`() {
        var registers = 0
        var imports = 0
        rule.setContent {
            AccountsScreen(
                onBack = {},
                onSignIn = {},
                onRegisterWhatsApp = { registers++ },
                onImportBackup = { imports++ },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("WhatsApp").assertExists()
        rule.onAllNodesWithText("Not registered").fetchSemanticsNodes()
            .let { assertEquals(true, it.isNotEmpty()) }
        // Signal's Register comes first; WhatsApp's is the second one.
        rule.onAllNodesWithText("Register")[1].performScrollTo().performClick()
        rule.onNodeWithText("Import backup (.crypt15)").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, registers)
        assertEquals(1, imports)
    }

    @Test
    fun `accounts rcs status and signal register report`() {
        var rcsStatus = 0
        var signalRegisters = 0
        rule.setContent {
            AccountsScreen(
                onBack = {}, onSignIn = {},
                onRegisterSignal = { signalRegisters++ },
                onShowRcsStatus = { rcsStatus++ },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Status").performScrollTo().performClick()
        rule.onAllNodesWithText("Register")[0].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, rcsStatus)
        assertEquals(1, signalRegisters)
    }

    @Test
    fun `whatsapp registration starts at the number step`() {
        rule.setContent {
            com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationScreen(
                onBack = {}, onRegistered = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Register WhatsApp").assertIsDisplayed()
        rule.onNodeWithText("Phone number").assertIsDisplayed()
        rule.onNodeWithText("Request SMS code").assertIsNotEnabled()
        rule.onNodeWithText("Phone number").performTextInput("5550100")
        rule.waitForIdle()
        rule.onNodeWithText("Request SMS code").assertIsEnabled()
    }

    @Test
    fun `whatsapp registration filters non-digits from the number`() {
        rule.setContent {
            com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationScreen(
                onBack = {}, onRegistered = {},
            )
        }
        rule.onNodeWithText("Phone number").performTextInput("55a5")
        rule.waitForIdle()
        rule.onNodeWithText("555").assertIsDisplayed()
        rule.onNodeWithText("55a5").assertDoesNotExist()
    }

    @Test
    fun `signal registration starts at the number step`() {
        rule.setContent {
            com.vayunmathur.communicate.ui.signal.SignalRegistrationScreen(
                onBack = {}, onRegistered = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Register Signal").assertIsDisplayed()
        rule.onNodeWithText("Phone number").assertIsDisplayed()
        rule.onNodeWithText("Request SMS code").assertIsNotEnabled()
        rule.onNodeWithText("Phone number").performTextInput("5550100")
        rule.waitForIdle()
        rule.onNodeWithText("Request SMS code").assertIsEnabled()
    }

    @Test
    fun `backup import screen explains and idles without a key`() {
        rule.setContent {
            com.vayunmathur.communicate.ui.whatsapp.WhatsAppBackupImportScreen(onBack = {})
        }
        rule.waitForIdle()
        rule.onNodeWithText("Import WhatsApp backup", substring = true).assertIsDisplayed()
    }
}
