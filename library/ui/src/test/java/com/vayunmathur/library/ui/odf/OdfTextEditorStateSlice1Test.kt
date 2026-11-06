package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stateful editor: edits commit, undo/redo traverse history, and the
 * undo cap bounds memory.
 * Slice-1 coverage for library/ui (odf package).
 */
class OdfTextEditorStateSlice1Test {

    private fun doc(vararg texts: String) = OdfDocument.TextDocument(
        title = "t",
        content = texts.map { OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(it)))) },
    )

    private fun textOf(d: OdfDocument.TextDocument, i: Int = 0): String =
        (d.content[i] as OdfContentBlock.Paragraph).paragraph.spans.joinToString("") { it.text }

    @Test
    fun `fresh state cannot undo or redo`() {
        val s = OdfTextEditorState(doc("a"))
        assertFalse(s.canUndo.value)
        assertFalse(s.canRedo.value)
        s.undo()
        s.redo()
        assertEquals("a", textOf(s.current))
    }

    @Test
    fun `an edit commits and undoes`() {
        val s = OdfTextEditorState(doc("a"))
        s.updateParagraphRun(0, 0, "b")
        assertEquals("b", textOf(s.current))
        assertTrue(s.canUndo.value)
        s.undo()
        assertEquals("a", textOf(s.current))
        assertFalse(s.canUndo.value)
        assertTrue(s.canRedo.value)
        s.redo()
        assertEquals("b", textOf(s.current))
    }

    @Test
    fun `a no-op edit commits nothing`() {
        val s = OdfTextEditorState(doc("a"))
        s.updateParagraphRun(0, 0, "a")
        assertFalse(s.canUndo.value)
    }

    @Test
    fun `a new edit clears the redo stack`() {
        val s = OdfTextEditorState(doc("a"))
        s.updateParagraphRun(0, 0, "b")
        s.undo()
        assertTrue(s.canRedo.value)
        s.updateParagraphRun(0, 0, "c")
        assertFalse(s.canRedo.value)
        assertEquals("c", textOf(s.current))
    }

    @Test
    fun `paragraph ops flow through the state`() {
        val s = OdfTextEditorState(doc("a", "b"))
        s.toggleListItem(0)
        assertEquals(
            ParagraphStyle.LIST_ITEM,
            (s.current.content[0] as OdfContentBlock.Paragraph).paragraph.style,
        )
        s.undo()
        assertEquals(
            ParagraphStyle.BODY,
            (s.current.content[0] as OdfContentBlock.Paragraph).paragraph.style,
        )
        s.setParagraphStyle(1, ParagraphStyle.HEADING1)
        assertEquals(
            ParagraphStyle.HEADING1,
            (s.current.content[1] as OdfContentBlock.Paragraph).paragraph.style,
        )
    }

    @Test
    fun `smart enter returns the caret and commits`() {
        val s = OdfTextEditorState(doc("hello"))
        val caret = s.handleListEnter(0, 0, 2)
        assertEquals(3, caret)
        assertEquals(2, s.current.content.size)
        assertNull(s.handleListEnter(0, 9, 0))
    }

    @Test
    fun `queries read through without committing`() {
        val s = OdfTextEditorState(doc("hello"))
        assertTrue(s.runRangeHasFormat(0, 0, 0, 5) { !it.bold })
        assertEquals(0, s.runParagraphIndexAt(0, 0, 2))
        assertFalse(s.canUndo.value)
    }
}
