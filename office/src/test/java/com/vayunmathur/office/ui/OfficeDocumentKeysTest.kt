package com.vayunmathur.office.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Direct tests for [handleOfficeDocumentKey] and [DocumentOverlayState.anyDialogOpen]. */
@RunWith(RobolectricTestRunner::class)
class OfficeDocumentKeysTest {

    private class Sink {
        var saved = 0
        var bold = 0
        var italic = 0
        var boldResult = true
    }

    private fun event(keyCode: Int, meta: Int = 0, action: Int = AndroidKeyEvent.ACTION_DOWN): KeyEvent =
        KeyEvent(AndroidKeyEvent(0L, 0L, action, keyCode, 0, meta))

    private fun handle(e: KeyEvent, dialogOpen: Boolean = false, sink: Sink = Sink()): Pair<Boolean, Sink> {
        val handled = handleOfficeDocumentKey(
            event = e,
            dialogOpen = dialogOpen,
            onSave = { sink.saved++; true },
            onToggleBold = { sink.bold++; sink.boldResult },
            onToggleItalic = { sink.italic++; true },
        )
        return handled to sink
    }

    private val ctrl = AndroidKeyEvent.META_CTRL_ON
    private val meta = AndroidKeyEvent.META_META_ON

    @Test
    fun ctrlAndMetaSave() {
        val v = event(AndroidKeyEvent.KEYCODE_S, ctrl)
        assertTrue(handle(v).first)
        assertEquals(1, handle(v).second.saved)
        assertEquals(1, handle(event(AndroidKeyEvent.KEYCODE_S, meta)).second.saved)
    }

    @Test
    fun ctrlAndMetaBoldItalic() {
        assertEquals(1, handle(event(AndroidKeyEvent.KEYCODE_B, ctrl)).second.bold)
        assertEquals(1, handle(event(AndroidKeyEvent.KEYCODE_I, meta)).second.italic)
    }

    @Test
    fun decliningActionIsNotConsumed() {
        val sink = Sink().apply { boldResult = false }
        assertFalse(handle(event(AndroidKeyEvent.KEYCODE_B, ctrl), sink = sink).first)
        assertEquals(1, sink.bold)
    }

    @Test
    fun dialogOpenBlocksAllShortcuts() {
        val (handled, sink) = handle(event(AndroidKeyEvent.KEYCODE_S, ctrl), dialogOpen = true)
        assertFalse(handled)
        assertEquals(0, sink.saved)
    }

    @Test
    fun plainUnknownAndKeyUpAreNotHandled() {
        assertFalse(handle(event(AndroidKeyEvent.KEYCODE_B)).first)
        assertFalse(handle(event(AndroidKeyEvent.KEYCODE_X, ctrl)).first)
        assertFalse(handle(event(AndroidKeyEvent.KEYCODE_S, ctrl, AndroidKeyEvent.ACTION_UP)).first)
    }

    // ---- Focus traversal (Tab / Shift-Tab / Escape) ----

    private class FocusSink {
        var next = 0
        var previous = 0
        var escape = 0
        var nextResult = true
        var escapeResult = true
    }

    private fun traverse(
        e: KeyEvent, dialogOpen: Boolean = false, sink: FocusSink = FocusSink(),
    ): Pair<Boolean, FocusSink> {
        val handled = handleOfficeFocusTraversalKey(
            event = e,
            dialogOpen = dialogOpen,
            onNext = { sink.next++; sink.nextResult },
            onPrevious = { sink.previous++; true },
            onEscapeToBody = { sink.escape++; sink.escapeResult },
        )
        return handled to sink
    }

    private val shift = AndroidKeyEvent.META_SHIFT_ON

    @Test
    fun tabMovesNextAndShiftTabMovesPrevious() {
        val (_, tabSink) = traverse(event(AndroidKeyEvent.KEYCODE_TAB))
        assertEquals(1, tabSink.next)
        assertEquals(0, tabSink.previous)
        val (_, shiftSink) = traverse(event(AndroidKeyEvent.KEYCODE_TAB, shift))
        assertEquals(0, shiftSink.next)
        assertEquals(1, shiftSink.previous)
    }

    @Test
    fun escapeReturnsToBody() {
        val (handled, sink) = traverse(event(AndroidKeyEvent.KEYCODE_ESCAPE))
        assertTrue(handled)
        assertEquals(1, sink.escape)
    }

    @Test
    fun traversalSuppressedWhileDialogOrMenuOpen() {
        // Tab is swallowed (consumed, no movement) so Compose's default Tab
        // handling cannot move editor focus behind the overlay...
        val (tabHandled, tabSink) = traverse(event(AndroidKeyEvent.KEYCODE_TAB), dialogOpen = true)
        assertTrue(tabHandled)
        assertEquals(0, tabSink.next + tabSink.previous + tabSink.escape)
        // ...while Escape is left to the overlay's own dismissal handling.
        val (escHandled, escSink) = traverse(event(AndroidKeyEvent.KEYCODE_ESCAPE), dialogOpen = true)
        assertFalse(escHandled)
        assertEquals(0, escSink.escape)
    }

    @Test
    fun decliningTraversalIsNotConsumed() {
        val sink = FocusSink().apply { nextResult = false; escapeResult = false }
        assertFalse(traverse(event(AndroidKeyEvent.KEYCODE_TAB), sink = sink).first)
        assertFalse(traverse(event(AndroidKeyEvent.KEYCODE_ESCAPE), sink = sink).first)
        assertEquals(1, sink.next)
        assertEquals(1, sink.escape)
    }

    @Test
    fun traversalIgnoresOtherKeysKeyUpAndModifiedTab() {
        assertFalse(traverse(event(AndroidKeyEvent.KEYCODE_A)).first)
        assertFalse(traverse(event(AndroidKeyEvent.KEYCODE_TAB, 0, AndroidKeyEvent.ACTION_UP)).first)
        // Ctrl/Meta+Tab belongs to the window/OS, not the editor.
        assertFalse(traverse(event(AndroidKeyEvent.KEYCODE_TAB, ctrl)).first)
        assertFalse(traverse(event(AndroidKeyEvent.KEYCODE_TAB, meta)).first)
        assertEquals(0, traverse(event(AndroidKeyEvent.KEYCODE_X)).second.next)
    }

    @Test
    fun anyDialogOpenReflectsOverlayFlags() {
        val state = DocumentScreenState()
        assertFalse(state.anyDialogOpen())
        state.showComment = true
        assertTrue(state.anyDialogOpen())
        state.showComment = false
        state.cropSlideTarget = 0 to 1
        assertTrue(state.anyDialogOpen())
    }
}
