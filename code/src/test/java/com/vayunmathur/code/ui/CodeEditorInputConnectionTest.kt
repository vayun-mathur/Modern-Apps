package com.vayunmathur.code.ui

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour tests for the private CodeInputConnection inside CodeEditorInputNode.
 * The connection is an IME bridge with no public surface, so it is instantiated
 * reflectively over a real [View]; every assertion checks the exact
 * [TextFieldValue] the node emits (text + caret) and the composing-region
 * callbacks, i.e. what the editor model would actually receive from a keyboard.
 */
@RunWith(RobolectricTestRunner::class)
class CodeEditorInputConnectionTest {

    private class Harness(initial: TextFieldValue) {
        val emitted = mutableListOf<TextFieldValue>()
        val composingChanges = mutableListOf<TextRange?>()
        val node: Any
        val connection: InputConnection

        init {
            val nodeClass = Class.forName("com.vayunmathur.code.ui.CodeEditorInputNode")
            val ctor = nodeClass.getDeclaredConstructor(
                Boolean::class.javaPrimitiveType,
                TextFieldValue::class.java,
                Function1::class.java,
                Function1::class.java,
            ).apply { isAccessible = true }
            val onValue: (TextFieldValue) -> Unit = { emitted.add(it) }
            val onComposing: (TextRange?) -> Unit = { composingChanges.add(it) }
            node = ctor.newInstance(true, initial, onValue, onComposing)
            val connClass = Class.forName("com.vayunmathur.code.ui.CodeEditorInputNode\$CodeInputConnection")
            val connCtor = connClass.getDeclaredConstructor(nodeClass, View::class.java)
                .apply { isAccessible = true }
            val view = View(ApplicationProvider.getApplicationContext())
            connection = connCtor.newInstance(node, view) as InputConnection
        }

        fun nodeValue(): TextFieldValue {
            val f = node.javaClass.getDeclaredField("value").apply { isAccessible = true }
            return f.get(node) as TextFieldValue
        }

        fun callNode(name: String, vararg args: Any?) {
            val m: Method = node.javaClass.declaredMethods.first { it.name == name }
            m.isAccessible = true
            m.invoke(node, *args)
        }
    }

    private fun tf(text: String, selStart: Int, selEnd: Int = selStart) =
        TextFieldValue(text, TextRange(selStart, selEnd))

    @Test fun commitTextReplacesSelectionAndMovesCaretAfterIt() {
        val h = Harness(tf("hello world", 6, 11))
        assertTrue(h.connection.commitText("there", 1))
        assertEquals(tf("hello there", 11), h.emitted.last())
        assertEquals(h.emitted.last(), h.nodeValue())
        assertEquals(listOf<TextRange?>(null), h.composingChanges)
    }

    @Test fun commitTextWithNullInsertsNothingAndCollapsesSelection() {
        val h = Harness(tf("abcd", 1, 3))
        assertTrue(h.connection.commitText(null, 1))
        assertEquals(tf("ad", 1), h.emitted.last())
    }

    @Test fun commitTextCursorPositionZeroAndNegativePlaceCaretAtInsertionStart() {
        val h = Harness(tf("abcd", 4))
        h.connection.commitText("X", 0)
        assertEquals(tf("abcdX", 4), h.emitted.last())
        val h2 = Harness(tf("abcd", 2))
        h2.connection.commitText("YZ", -3)
        // caret = safeStart + newCursorPosition (may go before start, then clamped)
        assertEquals("abYZcd", h2.emitted.last().text)
        assertEquals(0, h2.emitted.last().selection.start)
    }

    @Test fun commitTextCursorPositionBeyondOneOffsetsFromEnd() {
        val h = Harness(tf("ab", 2))
        h.connection.commitText("XYZ", 3)
        // end=5, caret = 5 + (3-1) = 7 clamped to length 5
        assertEquals(tf("abXYZ", 5), h.emitted.last())
    }

    @Test fun setComposingTextKeepsComposingRegionAndReportsIt() {
        val h = Harness(tf("hello", 5))
        assertTrue(h.connection.setComposingText("!", 1))
        assertEquals(tf("hello!", 6), h.emitted.last())
        assertEquals(TextRange(5, 6), h.composingChanges.last())
        // A following commit replaces the composing region, not the caret selection.
        assertTrue(h.connection.commitText("?", 1))
        assertEquals(tf("hello?", 6), h.emitted.last())
        assertNull(h.composingChanges.last())
    }

    @Test fun setComposingTextEmptyClearsComposing() {
        val h = Harness(tf("hello", 5))
        h.connection.setComposingText("x", 1)
        h.connection.setComposingText("", 1)
        assertNull(h.composingChanges.last())
        assertEquals("hello", h.emitted.last().text)
    }

    @Test fun setComposingRegionClampsAndFinishClears() {
        val h = Harness(tf("abc", 0))
        assertTrue(h.connection.setComposingRegion(-5, 99))
        assertEquals(TextRange(0, 3), h.composingChanges.last())
        assertTrue(h.connection.finishComposingText())
        assertNull(h.composingChanges.last())
        // Committing after finish uses the selection again.
        h.connection.setSelection(1, 1)
        h.connection.commitText("Z", 1)
        assertEquals(tf("aZbc", 2), h.emitted.last())
    }

    @Test fun deleteSurroundingTextDeletesBeforeAndAfterSelection() {
        val h = Harness(tf("hello world", 5, 6))
        assertTrue(h.connection.deleteSurroundingText(2, 3))
        // start=5-2=3, end=6+3=9 -> removes "lo wo", caret at 3
        assertEquals(tf("helld", 3), h.emitted.last())
        assertNull(h.composingChanges.last())
    }

    @Test fun deleteSurroundingTextClampsAtEdgesAndCodePointsVariantMatches() {
        val h = Harness(tf("ab", 0, 2))
        assertTrue(h.connection.deleteSurroundingTextInCodePoints(10, 10))
        assertEquals(tf("", 0), h.emitted.last())
        val h2 = Harness(tf("abcd", 2))
        h2.connection.deleteSurroundingText(0, 0)
        assertEquals(tf("abcd", 2), h2.emitted.last())
    }

    @Test fun setSelectionClampsIntoText() {
        val h = Harness(tf("abc", 0))
        assertTrue(h.connection.setSelection(-2, 99))
        assertEquals(TextRange(0, 3), h.emitted.last().selection)
        assertEquals("abc", h.emitted.last().text)
    }

    @Test fun sendKeyEventDeleteRemovesSelectionThenPreviousCharThenNoOpsAtStart() {
        val del = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        val h = Harness(tf("hello", 1, 4))
        assertTrue(h.connection.sendKeyEvent(del))
        assertEquals(tf("ho", 1), h.emitted.last())

        val h2 = Harness(tf("ab", 2))
        h2.connection.sendKeyEvent(del)
        assertEquals(tf("a", 1), h2.emitted.last())

        val h3 = Harness(tf("ab", 0))
        h3.connection.sendKeyEvent(del)
        assertTrue(h3.emitted.isEmpty())
    }

    @Test fun sendKeyEventEnterAndEditorActionInsertNewline() {
        val h = Harness(tf("ab", 1))
        assertTrue(h.connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertEquals(tf("a\nb", 2), h.emitted.last())
        val h2 = Harness(tf("ab", 2))
        assertTrue(h2.connection.performEditorAction(0))
        assertEquals(tf("ab\n", 3), h2.emitted.last())
        val h3 = Harness(tf("ab", 0))
        h3.connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(tf("\nab", 1), h3.emitted.last())
    }

    @Test fun sendKeyEventUnicodeCharTypesAndUpActionAndNullAreIgnored() {
        val h = Harness(tf("", 0))
        assertTrue(h.connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
        assertEquals(1, h.emitted.size)
        assertEquals("a", h.emitted.last().text)
        assertTrue(h.connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)))
        assertTrue(h.connection.sendKeyEvent(null))
        assertEquals(1, h.emitted.size)
    }

    @Test fun queriesReadFromCurrentValue() {
        val h = Harness(tf("hello world", 5, 7))
        assertEquals("hello", h.connection.getTextBeforeCursor(10, 0).toString())
        assertEquals("llo", h.connection.getTextBeforeCursor(3, 0).toString())
        // selection.max = 7, so after-cursor text starts at index 7
        assertEquals("orld", h.connection.getTextAfterCursor(10, 0).toString())
        assertEquals("or", h.connection.getTextAfterCursor(2, 0).toString())
        assertEquals(" w", h.connection.getSelectedText(0)?.toString())
        assertEquals(0, h.connection.getCursorCapsMode(0xffff))
    }

    @Test fun selectedTextNullWhenCollapsedAndExtractedTextMirrorsValue() {
        val h = Harness(tf("abc", 1))
        assertNull(h.connection.getSelectedText(0))
        val extracted: ExtractedText = h.connection.getExtractedText(ExtractedTextRequest(), 0)
        assertEquals("abc", extracted.text.toString())
        assertEquals(1, extracted.selectionStart)
        assertEquals(1, extracted.selectionEnd)
        assertEquals(0, extracted.startOffset)
        assertEquals(-1, extracted.partialStartOffset)
        assertEquals(-1, extracted.partialEndOffset)
    }

    @Test fun batchAndCursorUpdateContracts() {
        val h = Harness(tf("abc", 0))
        assertTrue(h.connection.beginBatchEdit())
        assertFalse(h.connection.endBatchEdit())
        assertFalse(h.connection.requestCursorUpdates(1))
    }

    @Test fun nodeUpdateDisablingStopsAndClearsComposingAndDetachIsSafe() {
        val h = Harness(tf("abc", 3))
        h.connection.setComposingText("x", 1)
        assertEquals(TextRange(3, 4), h.composingChanges.last())
        h.callNode("update", false, tf("abcx", 4), h.emitted.let { list ->
            val f: (TextFieldValue) -> Unit = { list.add(it) }; f
        }, h.composingChanges.let { list ->
            val f: (TextRange?) -> Unit = { list.add(it) }; f
        })
        assertNull(h.composingChanges.last())
        h.callNode("onDetach")
        // Unfocused focus events are a no-op.
        val fs = object : FocusState {
            override val isFocused = false
            override val isCaptured = false
            override val hasFocus = false
        }
        h.callNode("onFocusEvent", fs)
        assertNull(h.composingChanges.last())
    }
}
