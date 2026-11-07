package com.vayunmathur.communicate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateContact
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UiPass3 coverage for the dialer (`DialerContent.kt`): contact rows,
 * T9 filtering through the pad, and place-call taps. The pad itself is
 * covered in `CommunicateUiPass3Test`.
 */
@RunWith(RobolectricTestRunner::class)
class DialerUiPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int): String = context.getString(id)

    private val contacts = listOf(
        CommunicateContact(1L, "Alice Adams", "+155501", "Mobile"),
        CommunicateContact(2L, "Bob Brown", "+155502", "Work"),
        CommunicateContact(3L, "Carol Clark", "+155503", "Mobile"),
    )

    @Test
    fun `contacts render with numbers and call on tap`() {
        val placed = mutableListOf<String>()
        rule.setContent { DialerContent(contacts = contacts, onPlace = { placed.add(it) }) }
        rule.onNodeWithText("Alice Adams").assertIsDisplayed()
        rule.onNodeWithText("Mobile  +155501").assertIsDisplayed()
        rule.onNodeWithText("Alice Adams").performClick()
        rule.waitForIdle()
        assertEquals(listOf("+155501"), placed)
    }

    @Test
    fun `typing digits t9-filters the list`() {
        rule.setContent { DialerContent(contacts = contacts, onPlace = {}) }
        // 2 = ABC: matches Alice/Bob/Carol names; narrow with 5 (JKL) after.
        rule.onNodeWithText("2").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Alice Adams").assertIsDisplayed()
        rule.onNodeWithText("5").performClick()
        rule.waitForIdle()
        // "25" (A/B/C + J/K/L): Alice (Al) still matches, Bob (no l/k/j second) drops.
        rule.onNodeWithText("Alice Adams").assertIsDisplayed()
        rule.onNodeWithText("Bob Brown").assertDoesNotExist()
    }

    @Test
    fun `digits with no match show the filtered empty state`() {
        rule.setContent { DialerContent(contacts = contacts, onPlace = {}) }
        val nine = androidx.compose.ui.test.hasText("9") and androidx.compose.ui.test.hasClickAction()
        rule.onNode(nine).performClick()
        rule.onNode(nine).performClick()
        rule.onNode(nine).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.empty_contacts_for_digits)).assertExists()
    }

    @Test
    fun `no contacts shows the empty state`() {
        rule.setContent { DialerContent(contacts = emptyList(), onPlace = {}) }
        rule.onNodeWithText(str(R.string.empty_contacts)).assertExists()
    }

    @Test
    fun `call button places the typed number`() {
        val placed = mutableListOf<String>()
        rule.setContent { DialerContent(contacts = emptyList(), onPlace = { placed.add(it) }) }
        rule.onNodeWithText("1").performClick()
        rule.onNodeWithText("5").performClick()
        rule.onNodeWithText("5").performClick()
        rule.onNode(
            androidx.compose.ui.test.hasClickAction() and
                androidx.compose.ui.test.hasContentDescriptionExactly("Call"),
        ).performClick()
        rule.waitForIdle()
        assertEquals(listOf("155"), placed)
    }
}
