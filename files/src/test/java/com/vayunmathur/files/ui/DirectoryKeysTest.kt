package com.vayunmathur.files.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Direct tests for [handleDirectoryKey]: focus-search, select-all and Escape ordering. */
@RunWith(RobolectricTestRunner::class)
class DirectoryKeysTest {

    private class Sink {
        val calls = mutableListOf<String>()
    }

    private fun event(keyCode: Int, meta: Int = 0, action: Int = AndroidKeyEvent.ACTION_DOWN): KeyEvent =
        KeyEvent(AndroidKeyEvent(0L, 0L, action, keyCode, 0, meta))

    private fun handle(
        e: KeyEvent,
        dialogOpen: Boolean = false,
        isSearchActive: Boolean = false,
        hasSelection: Boolean = false,
        sink: Sink = Sink(),
    ): Pair<Boolean, Sink> {
        val handled = handleDirectoryKey(
            event = e,
            dialogOpen = dialogOpen,
            isSearchActive = isSearchActive,
            hasSelection = hasSelection,
            onFocusSearch = { sink.calls += "focusSearch" },
            onSelectAll = { sink.calls += "selectAll" },
            onClearSearch = { sink.calls += "clearSearch" },
            onClearSelection = { sink.calls += "clearSelection" },
        )
        return handled to sink
    }

    private val ctrl = AndroidKeyEvent.META_CTRL_ON
    private val metaKey = AndroidKeyEvent.META_META_ON

    @Test
    fun ctrlAndMetaFocusSearch() {
        assertEquals(listOf("focusSearch"), handle(event(AndroidKeyEvent.KEYCODE_F, ctrl)).second.calls)
        assertEquals(listOf("focusSearch"), handle(event(AndroidKeyEvent.KEYCODE_L, ctrl)).second.calls)
        assertEquals(listOf("focusSearch"), handle(event(AndroidKeyEvent.KEYCODE_F, metaKey)).second.calls)
    }

    @Test
    fun ctrlASelectsAllOnlyOutsideSearch() {
        assertTrue(handle(event(AndroidKeyEvent.KEYCODE_A, ctrl)).first)
        assertEquals(listOf("selectAll"), handle(event(AndroidKeyEvent.KEYCODE_A, ctrl)).second.calls)
        // Inside the search field, Ctrl+A keeps selecting the query text.
        val (handled, sink) = handle(event(AndroidKeyEvent.KEYCODE_A, ctrl), isSearchActive = true)
        assertFalse(handled)
        assertEquals(emptyList(), sink.calls)
    }

    @Test
    fun escapeClearsSearchBeforeSelection() {
        val (_, s1) = handle(
            event(AndroidKeyEvent.KEYCODE_ESCAPE), isSearchActive = true, hasSelection = true)
        assertEquals(listOf("clearSearch"), s1.calls)
        val (_, s2) = handle(event(AndroidKeyEvent.KEYCODE_ESCAPE), hasSelection = true)
        assertEquals(listOf("clearSelection"), s2.calls)
        assertFalse(handle(event(AndroidKeyEvent.KEYCODE_ESCAPE)).first)
    }

    @Test
    fun dialogOpenPlainUnknownAndKeyUpAreNotHandled() {
        assertFalse(handle(event(AndroidKeyEvent.KEYCODE_F, ctrl), dialogOpen = true).first)
        assertFalse(handle(event(AndroidKeyEvent.KEYCODE_ESCAPE), dialogOpen = true).first)
        assertFalse(handle(event(AndroidKeyEvent.KEYCODE_F)).first)
        assertFalse(handle(event(AndroidKeyEvent.KEYCODE_X, ctrl)).first)
        assertFalse(handle(event(AndroidKeyEvent.KEYCODE_F, ctrl, AndroidKeyEvent.ACTION_UP)).first)
    }
}
