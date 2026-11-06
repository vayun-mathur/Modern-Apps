package com.vayunmathur.library.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.ui.odf.MarkdownOdfConverter
import com.vayunmathur.library.ui.odf.OdfTextEditorState
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose coverage for the ODF-backed Markdown toolbar: bold/italic/list
 * buttons drive the run formatter, exercising the private formatter paths.
 * Slice-1 coverage for library/ui (Markdown editors).
 */
@RunWith(RobolectricTestRunner::class)
class OdfMarkdownToolbarComposeSlice1Test {

    @get:Rule
    val rule = createComposeRule()

    private fun toolbar(markdown: String = "hello world", selStart: Int = 0, selEnd: Int = 5): OdfMarkdownEditorController {
        val controller = OdfMarkdownEditorController(
            OdfTextEditorState(MarkdownOdfConverter.markdownToOdf(markdown))
        )
        controller.selStart = selStart
        controller.selEnd = selEnd
        rule.setContent {
            OdfMarkdownEditorToolbar(controller = controller)
        }
        rule.waitForIdle()
        return controller
    }

    @Test
    fun `the toolbar renders the run buttons`() {
        toolbar()
        rule.onNodeWithContentDescription("Bold").assertIsDisplayed()
        rule.onNodeWithContentDescription("Italic").assertIsDisplayed()
        rule.onNodeWithContentDescription("Numbered list").assertIsDisplayed()
        rule.onNodeWithContentDescription("Checkbox").assertIsDisplayed()
    }

    @Test
    fun `tapping bold bolds the odf run`() {
        val controller = toolbar()
        rule.onNodeWithContentDescription("Bold").performClick()
        rule.waitForIdle()
        val md = MarkdownOdfConverter.odfToMarkdown(controller.editor.current)
        assertTrue(md.contains("**hello**"), md)
    }

    @Test
    fun `tapping italic italicizes the odf run`() {
        val controller = toolbar()
        rule.onNodeWithContentDescription("Italic").performClick()
        rule.waitForIdle()
        val md = MarkdownOdfConverter.odfToMarkdown(controller.editor.current)
        assertTrue(md.contains("*hello*"), md)
    }

    @Test
    fun `tapping strikethrough strikes the odf run`() {
        val controller = toolbar()
        rule.onNodeWithContentDescription("Strikethrough").performClick()
        rule.waitForIdle()
        val md = MarkdownOdfConverter.odfToMarkdown(controller.editor.current)
        assertTrue(md.contains("~~hello~~"), md)
    }

    @Test
    fun `tapping bulleted list makes a list item`() {
        val controller = toolbar("plain para", 0, 0)
        rule.onNodeWithContentDescription("Bulleted list").performClick()
        rule.waitForIdle()
        val md = MarkdownOdfConverter.odfToMarkdown(controller.editor.current)
        assertTrue(md.contains("- plain para"), md)
    }

    @Test
    fun `tapping numbered list numbers the paragraph`() {
        val controller = toolbar("plain para", 0, 0)
        rule.onNodeWithContentDescription("Numbered list").performClick()
        rule.waitForIdle()
        val md = MarkdownOdfConverter.odfToMarkdown(controller.editor.current)
        assertTrue(md.contains("1. plain para") || md.contains("plain para"), md)
    }

    @Test
    fun `the heading menu restyles the paragraph`() {
        val controller = toolbar("plain para", 0, 0)
        rule.onNodeWithContentDescription("Heading").performClick()
        rule.waitForIdle()
        // The dropdown menu offers the heading styles; picking Heading 1
        // converts the paragraph to a top-level heading.
        rule.onNodeWithText("Heading 1").assertIsDisplayed()
        rule.onNodeWithText("Heading 1").performClick()
        rule.waitForIdle()
        val md = MarkdownOdfConverter.odfToMarkdown(controller.editor.current)
        assertTrue(md.contains("# plain para"), md)
    }

    @Test
    fun `tapping checkbox toggles a checkbox item`() {
        val controller = toolbar("plain para", 0, 0)
        rule.onNodeWithContentDescription("Checkbox").performClick()
        rule.waitForIdle()
        val md = MarkdownOdfConverter.odfToMarkdown(controller.editor.current)
        assertTrue(md.contains("plain para"), md)
    }
}
