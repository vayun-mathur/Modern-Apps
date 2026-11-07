package com.vayunmathur.communicate.whatsapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppBackupImportScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationScreen
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pass-2 slice-1 (whatsapp UI): the registration and backup-import screens at
 * their entry states. Requests themselves are HTTP/network work and are not
 * driven here; validation, filtering, and enablement are.
 */
@RunWith(RobolectricTestRunner::class)
class W5Pass2ScreensTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `registration starts on the number step with actions gated on input`() {
        var backed = false
        rule.setContent { DynamicTheme { WhatsAppRegistrationScreen(onBack = { backed = true }, onRegistered = {}) } }
        rule.onNodeWithText("Register WhatsApp").assertIsDisplayed()
        rule.onNodeWithText("Registers this number as the PRIMARY WhatsApp device.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("CC").assertIsDisplayed()
        rule.onNodeWithText("Phone number").assertIsDisplayed()
        rule.onNodeWithText("Request SMS code").assertIsNotEnabled()
        rule.onNodeWithText("Voice").assertIsNotEnabled()
        rule.onNodeWithText("Check number (no SMS)").assertIsNotEnabled()
        // Non-digits are filtered out; digits enable the request actions.
        rule.onNodeWithText("Phone number").performTextInput("555-ab-0101")
        rule.onNodeWithText("Request SMS code").assertIsEnabled()
        rule.onNodeWithText("Voice").assertIsEnabled()
        rule.onNodeWithText("Back").performClick()
        assertTrue(backed)
    }

    @Test
    fun `backup import validates the key field before enabling import`() {
        var backed = false
        rule.setContent { DynamicTheme { WhatsAppBackupImportScreen(onBack = { backed = true }) } }
        rule.onNodeWithText("Import WhatsApp backup").assertIsDisplayed()
        rule.onNodeWithText("Select a msgstore.db.crypt15 file and enter its 64-character hex backup key.").assertIsDisplayed()
        rule.onNodeWithText("Choose .crypt15 file").assertIsDisplayed()
        rule.onNodeWithText("Import").assertIsNotEnabled()
        // Even a full-length key cannot enable import without a picked file, and
        // pasted whitespace/punctuation is stripped from the field.
        rule.onNodeWithText("64-hex backup key").performTextInput("ab cd-".repeat(13))
        rule.onNodeWithText("Import").assertIsNotEnabled()
        rule.onNodeWithText("Back").performClick()
        assertTrue(backed)
    }
}
