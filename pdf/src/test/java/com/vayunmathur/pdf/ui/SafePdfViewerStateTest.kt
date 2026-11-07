package com.vayunmathur.pdf.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SafePdfViewerStateTest {
    private fun state() = SafePdfViewerState(CoroutineScope(Dispatchers.Unconfined))

    @Test fun defaults() {
        val s = state()
        assertFalse(s.editMode); assertFalse(s.dirty); assertEquals(EditTool.SELECT, s.tool)
        assertEquals(ShapeKind.RECT_OUTLINE, s.shape); assertEquals(MarkupKind.HIGHLIGHT, s.markup)
        assertEquals(Color.Red, s.color); assertEquals(1f, s.opacity); assertEquals(2f, s.strokeWidth)
        assertTrue(s.undoStack.isEmpty()); assertEquals(0, s.pageCount)
    }

    @Test fun drawColorAppliesOpacity() {
        val s = state(); s.color = Color.Red; s.opacity = 0.5f
        assertEquals(0.5f, s.drawColor.alpha, 0.01f)
    }

    @Test fun markEditedBumpsOnlyThatPageAndDirties() {
        val s = state(); s.markEdited(2); s.markEdited(2); s.markEdited(3)
        assertEquals(2, s.pageVersions[2]); assertEquals(1, s.pageVersions[3]); assertTrue(s.dirty)
    }

    @Test fun registerCreatedIgnoresZeroAndClearsRedo() {
        val s = state()
        s.redoStack.add(EditAction(0, 9L, EditKind.ADDED))
        s.registerCreated(1, 0L); assertTrue(s.undoStack.isEmpty()); assertEquals(1, s.redoStack.size)
        s.registerCreated(1, 42L)
        assertEquals(listOf(EditAction(1, 42L, EditKind.ADDED)), s.undoStack.toList())
        assertTrue(s.redoStack.isEmpty())
    }

    @Test fun registerMovedRecordsRects() {
        val s = state(); s.registerMoved(0, 5L, listOf(0f, 0f, 1f, 1f), listOf(2f, 2f, 3f, 3f))
        assertEquals(EditKind.MOVED, s.undoStack.single().kind)
        assertEquals(listOf(2f, 2f, 3f, 3f), s.undoStack.single().newRect)
    }

    @Test fun addPolyPointDraftLifecycle() {
        val s = state()
        s.addPolyPoint(0, Offset(1f, 2f))
        assertEquals(PolyDraft(0, listOf(Offset(1f, 2f)), false), s.polyDraft)
        s.addPolyPoint(0, Offset(3f, 4f)); assertEquals(2, s.polyDraft!!.points.size)
        s.addPolyPoint(1, Offset(9f, 9f)); assertEquals(2, s.polyDraft!!.points.size) // other page ignored
        s.tool = EditTool.BEZIER
        val s2 = state(); s2.tool = EditTool.BEZIER; s2.addPolyPoint(0, Offset.Zero)
        assertTrue(s2.polyDraft!!.bezier)
    }

    @Test fun commitPolyDiscardsShortDraftAndClears() {
        val s = state(); s.addPolyPoint(0, Offset.Zero)
        s.commitPoly(null); assertNull(s.polyDraft)
        // >=2 points but no document: still clears
        s.addPolyPoint(0, Offset.Zero); s.addPolyPoint(0, Offset(1f, 1f))
        s.commitPoly(null); assertNull(s.polyDraft)
    }

    @Test fun commitTextWithNullsIsNoOp() {
        val s = state(); s.commitText(null, null)
        val session = TextSession(0, Offset.Zero, 12f, 0, null, TextFieldValue("hi", TextRange(2)))
        s.commitText(session, null) // no doc
        s.commitTextAndClear(null); assertNull(s.textSession)
    }

    @Test fun toggleEditModeFlipsAndClearsSelection() {
        val s = state(); s.selected = 0 to 5L
        s.toggleEditMode(null); assertTrue(s.editMode); assertNull(s.selected)
        s.toggleEditMode(null); assertFalse(s.editMode)
    }

    @Test fun deleteDuplicateApplyWithNullDocAreNoOps() {
        val s = state(); s.selected = 0 to 1L
        s.deleteSelected(null); assertEquals(0 to 1L, s.selected)
        s.duplicateSelected(null); s.applyRedactions(null)
        s.createNote(null, 0, Offset.Zero, "x"); assertNull(s.pendingNote)
        s.createCallout(null, 0, Offset.Zero, Offset(1f, 1f), "y"); assertNull(s.pendingCallout)
    }

    @Test fun undoRedoWithNullDocDoNotCrashOnEmpty() {
        val s = state(); s.undo(null); s.redo(null)
        assertTrue(s.undoStack.isEmpty() && s.redoStack.isEmpty())
    }

    @Test fun startTextReplacesSession() {
        val s = state()
        val a = TextSession(0, Offset.Zero, 12f, 0, null, TextFieldValue("a"))
        val b = TextSession(1, Offset(1f, 1f), 14f, 0, null, TextFieldValue("b"))
        s.startText(null, a); assertEquals(a, s.textSession)
        s.startText(null, b); assertEquals(b, s.textSession)
    }

    @Test fun onImagePickedWithoutTargetClearsTarget() {
        val s = state(); s.onImagePicked(null, appContext(), null); assertNull(s.imageTarget)
    }

    private fun appContext(): android.content.Context =
        androidx.test.core.app.ApplicationProvider.getApplicationContext()
}
