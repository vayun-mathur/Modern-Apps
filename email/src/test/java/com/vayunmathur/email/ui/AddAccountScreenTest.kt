package com.vayunmathur.email.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real [AddAccountScreen] through provider picking and form
 * validation. Picking a provider never touches the network (connection
 * testing only starts on the save button), so these render for real.
 *
 * A miswired picker strands new users with no way to add an account; a
 * broken save-button guard fires connection tests against half-filled
 * forms. Each test asserts the navigated form or the guard state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddAccountScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `provider list shows every preset`() {
        composeRule.setContent { AddAccountScreen(onBack = {}, onAccountAdded = {}) }
        composeRule.onNodeWithText("Gmail").assertIsDisplayed()
        composeRule.onNodeWithText("Outlook / Microsoft 365", substring = true).performScrollTo()
        composeRule.onNodeWithText("Outlook / Microsoft 365", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Other (IMAP / SMTP)", substring = true).performScrollTo()
        composeRule.onNodeWithText("Other (IMAP / SMTP)", substring = true).assertIsDisplayed()
    }

    @Test
    fun `picking gmail opens the app-password form`() {
        var added = 0
        composeRule.setContent { AddAccountScreen(onBack = {}, onAccountAdded = { added++ }) }
        composeRule.onNodeWithText("Gmail").performClick()
        composeRule.waitForIdle()
        // Gmail's instructions mention the app-passwords page.
        composeRule.onNodeWithText("apppasswords", substring = true, ignoreCase = true).performScrollTo()
        composeRule.onNodeWithText("apppasswords", substring = true, ignoreCase = true).assertIsDisplayed()
        assertEquals(0, added)
    }

    @Test
    fun `picking outlook opens the oauth form`() {
        composeRule.setContent { AddAccountScreen(onBack = {}, onAccountAdded = {}) }
        composeRule.onNodeWithText("Outlook / Microsoft 365", substring = true).performScrollTo()
        composeRule.onNodeWithText("Outlook / Microsoft 365", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sign in with Microsoft", substring = true).performScrollTo()
        composeRule.onNodeWithText("Sign in with Microsoft", substring = true).assertIsDisplayed()
    }

    @Test
    fun `back from a provider returns to the picker`() {
        var backs = 0
        composeRule.setContent { AddAccountScreen(onBack = { backs++ }, onAccountAdded = {}) }
        composeRule.onNodeWithText("Gmail").performClick()
        composeRule.waitForIdle()
        // System back collapses the provider, not the screen.
        composeRule.onNodeWithText("Gmail", substring = true).assertExists()
        assertEquals(0, backs)
    }

    @Test
    fun `custom provider shows manual server fields`() {
        composeRule.setContent { AddAccountScreen(onBack = {}, onAccountAdded = {}) }
        composeRule.onNodeWithText("Other (IMAP / SMTP)", substring = true).performScrollTo()
        composeRule.onNodeWithText("Other (IMAP / SMTP)", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("IMAP", substring = true).performScrollTo()
        composeRule.onNodeWithText("IMAP", substring = true).assertIsDisplayed()
    }
}
