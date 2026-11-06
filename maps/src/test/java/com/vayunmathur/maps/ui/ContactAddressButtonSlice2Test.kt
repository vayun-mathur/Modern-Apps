package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The contact-address shortcut button: it renders, and with no picker on the
 * device the tap degrades quietly.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class ContactAddressButtonSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the button shows`() {
        rule.setContent { ContactAddressButton(onAddress = {}) }
        rule.onNodeWithContentDescription("Contacts").assertIsDisplayed()
    }

    @Test
    fun `no chooser shows before any pick`() {
        rule.setContent { ContactAddressButton(onAddress = {}) }
        rule.onNodeWithText("Choose an address").assertDoesNotExist()
    }
}
