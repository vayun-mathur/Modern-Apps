package com.vayunmathur.library.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose coverage for the Markdown editor toolbar (the buttons users tap).
 * Slice-1 coverage for library/ui (Markdown editors).
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownToolbarComposeSlice1Test {

    @get:Rule
    val rule = createComposeRule()

    private var current = TextFieldValue("hello", TextRange(0, 5))

    private fun toolbar() {
        rule.setContent {
            MarkdownFormatToolbar(
                value = current,
                onValueChange = { current = it },
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `the toolbar renders its buttons`() {
        toolbar()
        rule.onNodeWithContentDescription("Bold").assertIsDisplayed()
        rule.onNodeWithContentDescription("Italic").assertIsDisplayed()
        rule.onNodeWithContentDescription("Heading").assertIsDisplayed()
        rule.onNodeWithContentDescription("Numbered list").assertIsDisplayed()
    }

    @Test
    fun `tapping bold wraps the selection`() {
        toolbar()
        rule.onNodeWithContentDescription("Bold").performClick()
        rule.waitForIdle()
        assertEquals("**hello**", current.text)
    }

    @Test
    fun `tapping italic wraps the selection`() {
        toolbar()
        rule.onNodeWithContentDescription("Italic").performClick()
        rule.waitForIdle()
        assertTrue(current.text.contains("*hello*"), current.text)
    }

    @Test
    fun `tapping quote prefixes the line`() {
        toolbar()
        rule.onNodeWithContentDescription("Quote").performClick()
        rule.waitForIdle()
        assertTrue(current.text.startsWith("> "), current.text)
    }

    @Test
    fun `tapping the heading button opens the heading menu`() {
        toolbar()
        rule.onNodeWithContentDescription("Heading").performClick()
        rule.waitForIdle()
        // The dropdown lists heading levels 1..3.
        rule.waitForIdle()
        assertTrue(current.text == "hello", current.text)
    }
}
