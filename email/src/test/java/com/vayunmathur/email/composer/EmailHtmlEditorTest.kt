package com.vayunmathur.email.composer

import androidx.compose.ui.test.junit4.createComposeRule
import com.vayunmathur.email.ui.composer.EmailHtmlEditor
import com.vayunmathur.email.ui.composer.EmailHtmlEditorController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [EmailHtmlEditor] AndroidView wiring: the factory must parse the
 * controller's HTML into the EditText (through the email tag handler so
 * rich formatting survives), bind the controller to that EditText, show
 * the placeholder as the hint, push user edits back into
 * [EmailHtmlEditorController.html] through the serializer, and re-render
 * when [EmailHtmlEditorController.setHtml] bumps the set-version.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailHtmlEditorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `factory renders controller html and binds the edit text`() {
        val controller = EmailHtmlEditorController("<p>Hello <b>bold</b> world</p>")
        composeRule.setContent {
            EmailHtmlEditor(controller = controller, placeholder = "Write your message")
        }
        composeRule.waitForIdle()
        val editText = assertNotNull(controller.editText)
        // A <p> block parses to its text plus a trailing paragraph newline.
        assertEquals("Hello bold world", editText.text.toString().trimEnd())
        assertEquals("Write your message", editText.hint.toString())
        // Cursor lands at the end of the loaded text.
        assertEquals(editText.text.length, editText.selectionStart)
    }

    @Test
    fun `typing in the edit text commits serialized html to the controller`() {
        val controller = EmailHtmlEditorController("")
        composeRule.setContent { EmailHtmlEditor(controller = controller) }
        composeRule.waitForIdle()
        val editText = assertNotNull(controller.editText)
        // Simulate the user typing: the TextWatcher must serialize the new
        // spanned text back into controller.html.
        composeRule.runOnUiThread { editText.setText("typed body") }
        composeRule.waitForIdle()
        assertTrue(controller.html.contains("typed body"), controller.html)
    }

    @Test
    fun `setHtml re-renders the edit text and keeps selection in range`() {
        val controller = EmailHtmlEditorController("<p>initial long text here</p>")
        composeRule.setContent { EmailHtmlEditor(controller = controller) }
        composeRule.waitForIdle()
        val editText = assertNotNull(controller.editText)
        composeRule.runOnUiThread { editText.setSelection(editText.text.length) }
        controller.setHtml("<p>short</p>")
        composeRule.waitForIdle()
        assertEquals("short", editText.text.toString().trimEnd())
        assertTrue(editText.selectionStart in 0..editText.text.length)
    }
}
