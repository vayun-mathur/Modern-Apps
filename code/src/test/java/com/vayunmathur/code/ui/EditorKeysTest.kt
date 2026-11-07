package com.vayunmathur.code.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Direct tests for [handleEditorKey]: ctrl actions, navigation, edits and multi-caret. */
@RunWith(RobolectricTestRunner::class)
class EditorKeysTest {

    private class Sink {
        var emitted: TextFieldValue? = null
        var moved: TextRange? = null
        var extras: List<Int>? = null
        var saved = 0
        var commented = 0
        var folded = 0
        var unfolded = 0
        var foldAt: Int? = null
    }

    private fun event(keyCode: Int, meta: Int = 0, action: Int = AndroidKeyEvent.ACTION_DOWN): KeyEvent =
        KeyEvent(AndroidKeyEvent(0L, 0L, action, keyCode, 0, meta))

    private fun handle(
        e: KeyEvent,
        value: TextFieldValue,
        extras: List<Int> = emptyList(),
        sink: Sink = Sink(),
    ): Pair<Boolean, Sink> {
        val handled = handleEditorKey(
            event = e, value = value, tabWidth = 4, extraCarets = extras,
            setExtraCarets = { sink.extras = it },
            emit = { sink.emitted = it },
            move = { sink.moved = it },
            onSave = { sink.saved++ },
            onComment = { sink.commented++ },
            foldAll = { sink.folded++ },
            unfoldAll = { sink.unfolded++ },
            toggleFoldAtCaret = { sink.foldAt = it },
        )
        return handled to sink
    }

    private val ctrl = AndroidKeyEvent.META_CTRL_ON
    private val ctrlShift = AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_SHIFT_ON

    @Test
    fun keyUpIsNotHandled() {
        val (handled, _) = handle(
            event(AndroidKeyEvent.KEYCODE_A, action = AndroidKeyEvent.ACTION_UP),
            TextFieldValue("abc", TextRange(1)),
        )
        assertFalse(handled)
    }

    @Test
    fun ctrlSaveAndComment() {
        val v = TextFieldValue("abc", TextRange(1))
        assertTrue(handle(event(AndroidKeyEvent.KEYCODE_S, ctrl), v).first)
        assertEquals(1, handle(event(AndroidKeyEvent.KEYCODE_S, ctrl), v).second.saved)
        val (_, sink) = handle(event(AndroidKeyEvent.KEYCODE_SLASH, ctrl), v)
        assertEquals(1, sink.commented)
    }

    @Test
    fun ctrlBracketsFold() {
        val v = TextFieldValue("abc", TextRange(2))
        val (_, s1) = handle(event(AndroidKeyEvent.KEYCODE_LEFT_BRACKET, ctrlShift), v)
        assertEquals(1, s1.folded)
        val (_, s2) = handle(event(AndroidKeyEvent.KEYCODE_RIGHT_BRACKET, ctrlShift), v)
        assertEquals(1, s2.unfolded)
        val (_, s3) = handle(event(AndroidKeyEvent.KEYCODE_LEFT_BRACKET, ctrl), v)
        assertEquals(2, s3.foldAt)
    }

    @Test
    fun ctrlUnknownReturnsFalse() {
        assertFalse(handle(event(AndroidKeyEvent.KEYCODE_X, ctrl), TextFieldValue("abc", TextRange(1))).first)
    }

    @Test
    fun ctrlDAddsCaretAtNextOccurrence() {
        val (_, sink) = handle(
            event(AndroidKeyEvent.KEYCODE_D, ctrl),
            TextFieldValue("foo bar foo", TextRange(1)),
        )
        // Word "foo" at caret; next occurrence ends at index 11.
        assertEquals(listOf(11), sink.extras)
    }

    @Test
    fun escapeDismissesOnlyWhenExtrasPresent() {
        val v = TextFieldValue("abc", TextRange(1))
        assertFalse(handle(event(AndroidKeyEvent.KEYCODE_ESCAPE), v).first)
        val (handled, sink) = handle(event(AndroidKeyEvent.KEYCODE_ESCAPE), v, extras = listOf(2))
        assertTrue(handled)
        assertEquals(emptyList(), sink.extras)
    }

    @Test
    fun tabIndentsAndShiftTabDedents() {
        val v = TextFieldValue("abc", TextRange(0))
        val (_, s1) = handle(event(AndroidKeyEvent.KEYCODE_TAB), v)
        assertEquals("    abc", s1.emitted?.text)
        val (_, s2) = handle(
            event(AndroidKeyEvent.KEYCODE_TAB, AndroidKeyEvent.META_SHIFT_ON),
            TextFieldValue("    abc", TextRange(4)),
        )
        assertEquals("abc", s2.emitted?.text)
    }

    @Test
    fun arrowsMoveCaretClamped() {
        val v = TextFieldValue("abc", TextRange(0))
        val (_, s1) = handle(event(AndroidKeyEvent.KEYCODE_DPAD_LEFT), v)
        assertEquals(TextRange(0), s1.moved)
        val (_, s2) = handle(event(AndroidKeyEvent.KEYCODE_DPAD_RIGHT), TextFieldValue("abc", TextRange(3)))
        assertEquals(TextRange(3), s2.moved)
    }

    @Test
    fun backspaceDeletesSelectionCharAndNoOpsAtStart() {
        val (_, s1) = handle(event(AndroidKeyEvent.KEYCODE_DEL), TextFieldValue("abc", TextRange(0, 2)))
        assertEquals("c", s1.emitted?.text)
        val (_, s2) = handle(event(AndroidKeyEvent.KEYCODE_DEL), TextFieldValue("abc", TextRange(2)))
        assertEquals("ac", s2.emitted?.text)
        val (_, s3) = handle(event(AndroidKeyEvent.KEYCODE_DEL), TextFieldValue("abc", TextRange(0)))
        assertEquals(null, s3.emitted)
    }

    @Test
    fun enterSplitsLine() {
        val (_, sink) = handle(event(AndroidKeyEvent.KEYCODE_ENTER), TextFieldValue("abcd", TextRange(2)))
        assertEquals("ab\ncd", sink.emitted?.text)
        assertEquals(TextRange(3), sink.emitted?.selection)
    }

    @Test
    fun printableCharInserts() {
        val (handled, sink) = handle(event(AndroidKeyEvent.KEYCODE_A), TextFieldValue("bc", TextRange(0)))
        assertTrue(handled, "char key should insert (emitted=${sink.emitted})")
        assertEquals("abc", sink.emitted?.text)
    }

    @Test
    fun multiCaretBackspaceDeletesBeforeEach() {
        val (_, sink) = handle(
            event(AndroidKeyEvent.KEYCODE_DEL),
            TextFieldValue("aXbXc", TextRange(1)),
            extras = listOf(3),
        )
        assertEquals("XXc", sink.emitted?.text)
    }
}
