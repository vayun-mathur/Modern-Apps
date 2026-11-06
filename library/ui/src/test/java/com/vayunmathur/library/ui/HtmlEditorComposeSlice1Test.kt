package com.vayunmathur.library.ui

import android.text.SpannableStringBuilder
import android.widget.EditText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose coverage for the HTML editor toolbar: buttons render and drive the
 * controller. Slice-1 coverage for library/ui (Html editors).
 */
@RunWith(RobolectricTestRunner::class)
class HtmlEditorComposeSlice1Test {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun controller(text: String): HtmlEditorController {
        val c = HtmlEditorController()
        c.editText = EditText(context).apply {
            setText(SpannableStringBuilder(text))
            setSelection(0, text.length)
        }
        return c
    }

    @Test
    fun `the toolbar renders the base buttons`() {
        val c = controller("hi")
        rule.setContent {
            HtmlFormatToolbar(controller = c)
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Bold").assertIsDisplayed()
        rule.onNodeWithContentDescription("Italic").assertIsDisplayed()
    }

    @Test
    fun `tapping bold in the toolbar bolds the selection`() {
        val c = controller("hello")
        rule.setContent {
            HtmlFormatToolbar(controller = c)
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Bold").performClick()
        rule.waitForIdle()
        assertTrue(c.isActive(EditorFormat.BOLD))
        assertTrue(c.editText!!.text.toString() == "hello")
    }

    @Test
    fun `tapping italic in the toolbar italicizes`() {
        val c = controller("hello")
        rule.setContent {
            HtmlFormatToolbar(controller = c)
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Italic").performClick()
        rule.waitForIdle()
        assertTrue(c.isActive(EditorFormat.ITALIC))
    }

    @Test
    fun `the field composes and wires the controller`() {
        val c = HtmlEditorController("<p>field text</p>")
        rule.setContent {
            HtmlEditor(controller = c, placeholder = "Type here")
        }
        rule.waitForIdle()
        // The AndroidView factory attaches the EditText to the controller.
        assertTrue(c.editText != null)
        assertTrue(c.editText!!.text.toString().contains("field text"))
    }

    @Test
    fun `rememberHtmlEditorController creates a controller`() {
        var html = ""
        rule.setContent {
            val c = rememberHtmlEditorController("<p>remembered</p>")
            html = c.html
        }
        rule.waitForIdle()
        assertTrue(html.contains("remembered"), html)
    }
}
