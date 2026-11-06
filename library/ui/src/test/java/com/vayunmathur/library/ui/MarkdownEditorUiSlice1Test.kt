package com.vayunmathur.library.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.vayunmathur.library.ui.odf.MarkdownOdfConverter
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTextEditorState
import com.vayunmathur.library.ui.odf.ParagraphStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Markdown body field seams: the toolbar formatter contract and the
 * ODF-backed editor's markdown round-trip.
 * Slice-1 coverage for library/ui (Markdown editors).
 *
 * Compose-rule tests are not possible in `:library:ui` yet (the ui-test
 * manifest entry does not merge into library unit tests — messaged to lead),
 * so these pin the controller/formatter layer the composables delegate to.
 */
class MarkdownEditorUiSlice1Test {

    @Test
    fun `the formatter bolds through the toolbar contract`() {
        var current = TextFieldValue("hello", TextRange(0, 5))
        val f = MarkdownFormatter(
            value = current,
            onValueChange = { current = it },
        )
        assertTrue(f.supported.contains(EditorFormat.BOLD))
        assertTrue(!f.isActive(EditorFormat.BOLD))
        f.toggle(EditorFormat.BOLD)
        assertEquals("**hello**", current.text)
        val f2 = MarkdownFormatter(value = current, onValueChange = {})
        assertTrue(f2.isActive(EditorFormat.BOLD))
    }

    @Test
    fun `the formatter toggles bullets and links`() {
        var current = TextFieldValue("item", TextRange(0, 4))
        val f = MarkdownFormatter(value = current, onValueChange = { current = it })
        f.toggle(EditorFormat.BULLET)
        assertEquals("- item", current.text)
        val link = MarkdownFormatter(
            value = TextFieldValue("docs", TextRange(0, 4)),
            onValueChange = {},
        )
        val ctx = link.linkContext()
        assertTrue(ctx != null && !ctx.editing && ctx.text == "docs")
    }

    // OdfMarkdownEditorController lives in the odf package alongside the editor.
    @Test
    fun `the odf-backed controller parses the initial markdown`() {
        val editor = OdfTextEditorState(MarkdownOdfConverter.markdownToOdf("# Title"))
        val controller = com.vayunmathur.library.ui.OdfMarkdownEditorController(editor)
        val doc: OdfDocument.TextDocument = controller.editor.current
        assertTrue(doc.content.isNotEmpty())
        val first = (doc.content[0] as OdfContentBlock.Paragraph).paragraph
        assertEquals(ParagraphStyle.HEADING1, first.style)
    }

    @Test
    fun `edits to the odf document convert back to markdown`() {
        val editor = OdfTextEditorState(MarkdownOdfConverter.markdownToOdf("# Title"))
        val controller = com.vayunmathur.library.ui.OdfMarkdownEditorController(editor)
        controller.editor.updateParagraphRun(0, 0, "# Title edited")
        val md = MarkdownOdfConverter.odfToMarkdown(controller.editor.current)
        assertTrue(md.contains("Title edited"), md)
    }

    @Test
    fun `requesting focus at end raises a caret request`() {
        val controller = OdfMarkdownEditorController(
            OdfTextEditorState(
                OdfDocument.TextDocument(
                    title = "",
                    content = listOf(
                        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("hi"))))
                    ),
                )
            )
        )
        controller.requestFocusAtEnd()
        assertEquals(Int.MAX_VALUE, controller.caretRequest)
        assertTrue(!controller.focused)
    }
}
