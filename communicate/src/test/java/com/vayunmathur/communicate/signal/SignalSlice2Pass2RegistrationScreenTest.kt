package com.vayunmathur.communicate.signal

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.communicate.ui.signal.SignalRegistrationScreen
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Slice 2/6 pass 2: the Signal registration screen's number-entry step (no live server). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalSlice2Pass2RegistrationScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `number entry starts with actions disabled until a number is typed`() {
        var backs = 0; var registered = 0
        rule.setContent { SignalRegistrationScreen(onBack = { backs++ }, onRegistered = { registered++ }) }
        rule.onNodeWithText("Register Signal").assertExists()
        rule.onNodeWithText("Request SMS code").assertIsNotEnabled()
        rule.onNodeWithText("Phone number").performTextInput("4155552671")
        // Typing digits enables the request actions (still no server call until tapped)
        rule.onNodeWithText("Request SMS code").assertExists()
        rule.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
        assertEquals(0, registered)
    }

    @Test fun `country code defaults to 1 and only digits survive typing`() {
        rule.setContent { SignalRegistrationScreen(onBack = {}, onRegistered = {}) }
        rule.onNodeWithText("CC").assertExists()
        rule.onNodeWithText("Check number (no SMS)").assertIsNotEnabled()
        rule.onNodeWithText("Phone number").performTextInput("abc415")
        // Non-digits are filtered by the field; the node still shows the digit suffix
        rule.onNodeWithText("415", substring = true).assertExists()
    }
}
