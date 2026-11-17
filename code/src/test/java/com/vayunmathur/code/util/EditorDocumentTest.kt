package com.vayunmathur.code.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.vayunmathur.code.domain.Edit
import com.vayunmathur.code.domain.EditorDocument

/** Unit tests for the pure line-indexed document model. */
class EditorDocumentTest {

    @Test
    fun lineCountAndBounds() {
        val doc = EditorDocument("a\nbb\nccc")
        assertEquals(3, doc.lineCount())
        assertEquals(0, doc.lineStart(0))
        assertEquals(1, doc.lineEnd(0))
        assertEquals(2, doc.lineStart(1))
        assertEquals(4, doc.lineEnd(1))
        assertEquals(5, doc.lineStart(2))
        assertEquals(8, doc.lineEnd(2))
    }

    @Test
    fun lineTextExcludesNewline() {
        val doc = EditorDocument("one\ntwo\n")
        assertEquals("one", doc.lineText(0))
        assertEquals("two", doc.lineText(1))
        assertEquals("", doc.lineText(2)) // trailing empty line after final newline
    }

    @Test
    fun lineOfOffsetAndColumn() {
        val doc = EditorDocument("ab\ncd")
        assertEquals(0, doc.lineOfOffset(0))
        assertEquals(0, doc.lineOfOffset(2))
        assertEquals(1, doc.lineOfOffset(3))
        assertEquals(1, doc.lineOfOffset(5))
        assertEquals(1, doc.columnOfOffset(4)) // 'd' is column 1 on line 1
    }

    @Test
    fun offsetOfClampsToLine() {
        val doc = EditorDocument("abc\nde")
        assertEquals(1, doc.offsetOf(0, 1))
        assertEquals(3, doc.offsetOf(0, 99)) // clamped to line end
        assertEquals(4, doc.offsetOf(1, 0))
    }

    @Test
    fun replaceUpdatesTextAndLineIndex() {
        val doc = EditorDocument("hello world")
        doc.replace(6, 11, "there")
        assertEquals("hello there", doc.text)
        doc.replace(5, 5, "\n")
        assertEquals(2, doc.lineCount())
        assertEquals("hello", doc.lineText(0))
        assertEquals(" there", doc.lineText(1))
    }

    @Test
    fun applyEditsRightToLeftKeepsOffsetsValid() {
        val doc = EditorDocument("0123456789")
        // Two non-overlapping edits given in ascending order; must still land correctly.
        doc.applyEdits(listOf(Edit(0, 1, "X"), Edit(9, 10, "Y")))
        assertEquals("X12345678Y", doc.text)
    }

    @Test
    fun applyEditsInsertionsAtMultipleCarets() {
        val doc = EditorDocument("a b c")
        // Insert '*' before each letter (carets at 0,2,4).
        doc.applyEdits(listOf(Edit(0, 0, "*"), Edit(2, 2, "*"), Edit(4, 4, "*")))
        assertEquals("*a *b *c", doc.text)
    }

    @Test
    fun undoRedoRoundTrips() {
        val doc = EditorDocument("start")
        assertFalse(doc.canUndo)
        doc.replace(5, 5, "!")
        assertEquals("start!", doc.text)
        assertTrue(doc.canUndo)
        doc.undo()
        assertEquals("start", doc.text)
        assertTrue(doc.canRedo)
        doc.redo()
        assertEquals("start!", doc.text)
    }

    @Test
    fun groupedEditsAreOneUndoStep() {
        val doc = EditorDocument("a b c")
        doc.applyEdits(listOf(Edit(0, 0, "*"), Edit(2, 2, "*")))
        doc.undo()
        assertEquals("a b c", doc.text)
    }
}
