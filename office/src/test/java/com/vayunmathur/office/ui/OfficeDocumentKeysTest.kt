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
