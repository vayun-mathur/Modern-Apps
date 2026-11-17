@file:OptIn(ExperimentalComposeUiApi::class)

package com.vayunmathur.code.ui

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Binds the on-screen (system) keyboard to the Canvas-based [CodeEditorView].
 *
 * This is the same mechanism [androidx.compose.foundation.text.BasicTextField] uses under the
 * hood: a [PlatformTextInputModifierNode] that, while the editor holds focus, runs an input
 * session ([establishTextInputSession]) whose [InputConnection] translates IME operations
 * (commit/compose/delete/send-key) into edits on our model. Every mutation is routed through the
 * same [onValueChange] sink the rest of the editor uses, so smart input, undo/redo and auto-save
 * all keep working. Composing (in-progress) text is reported back through [onComposingChange] so
 * the Canvas can underline it, and selection/caret are reported to the IME for cursor tracking.
 *
 * Only the primary caret is bound to the IME; secondary carets remain a post-commit fan-out.
 */
fun Modifier.codeEditorInput(
    enabled: Boolean,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onComposingChange: (TextRange?) -> Unit,
): Modifier = this then CodeEditorInputElement(enabled, value, onValueChange, onComposingChange)

private data class CodeEditorInputElement(
    val enabled: Boolean,
    val value: TextFieldValue,
    val onValueChange: (TextFieldValue) -> Unit,
    val onComposingChange: (TextRange?) -> Unit,
) : ModifierNodeElement<CodeEditorInputNode>() {
    override fun create() = CodeEditorInputNode(enabled, value, onValueChange, onComposingChange)

    override fun InspectorInfo.inspectableProperties() {
        name = "codeEditorInput"
        properties["enabled"] = enabled
    }

    override fun update(node: CodeEditorInputNode) {
        node.update(enabled, value, onValueChange, onComposingChange)
    }
}

private class CodeEditorInputNode(
    private var enabled: Boolean,
    var value: TextFieldValue,
    private var onValueChange: (TextFieldValue) -> Unit,
    private var onComposingChange: (TextRange?) -> Unit,
) : Modifier.Node(),
    PlatformTextInputModifierNode,
    FocusEventModifierNode,
    CompositionLocalConsumerModifierNode {

    private var sessionJob: Job? = null
    private var focused = false

    /** In-progress IME composition region, or null. Also mirrored to the Canvas via callback. */
    private var composing: TextRange? = null

    fun update(
        enabled: Boolean,
        value: TextFieldValue,
        onValueChange: (TextFieldValue) -> Unit,
        onComposingChange: (TextRange?) -> Unit,
    ) {
        this.enabled = enabled
        this.value = value
        this.onValueChange = onValueChange
        this.onComposingChange = onComposingChange
        if (!enabled) stopSession()
        else if (focused && sessionJob == null) startSession()
    }

    override fun onFocusEvent(focusState: FocusState) {
        val nowFocused = focusState.isFocused
        if (nowFocused == focused) return
        focused = nowFocused
        if (focused && enabled) startSession() else stopSession()
    }

    override fun onDetach() {
        stopSession()
    }

    private fun startSession() {
        if (sessionJob != null) return
        sessionJob = coroutineScope.launch {
            establishTextInputSession {
                val request = object : PlatformTextInputMethodRequest {
                    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                        outAttributes.inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                            EditorInfo.IME_ACTION_NONE
                        outAttributes.initialSelStart = value.selection.min
                        outAttributes.initialSelEnd = value.selection.max
                        val view = currentValueOf(LocalView)
                        return CodeInputConnection(view)
                    }
                }
                startInputMethod(request)
            }
        }
    }

    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        if (composing != null) {
            composing = null
            onComposingChange(null)
        }
    }

    private fun setComposing(range: TextRange?) {
        composing = range
        onComposingChange(range)
    }

    /** Emits [new] and keeps the node's cached value in sync for the next IME callback. */
    private fun emit(new: TextFieldValue) {
        value = new
        onValueChange(new)
    }

    /**
     * A minimal [InputConnection] over our [TextFieldValue] model. Text edits replace the current
     * composition (or the selection) and route through [emit]; queries read straight from [value].
     */
    private inner class CodeInputConnection(view: View) : BaseInputConnection(view, true) {

        private fun replaceRegion(): Pair<Int, Int> {
            composing?.let { return it.min to it.max }
            return value.selection.min to value.selection.max
        }

        private fun replaceAndEmit(replacement: String, newCursorPosition: Int, keepComposing: Boolean) {
            val v = value
            val (rs, re) = replaceRegion()
            val text = v.text
            val safeStart = rs.coerceIn(0, text.length)
            val safeEnd = re.coerceIn(safeStart, text.length)
            val newText = text.substring(0, safeStart) + replacement + text.substring(safeEnd)
            val end = safeStart + replacement.length
            val caret = if (newCursorPosition > 0) end + (newCursorPosition - 1) else safeStart + newCursorPosition
            setComposing(if (keepComposing && replacement.isNotEmpty()) TextRange(safeStart, end) else null)
            emit(TextFieldValue(newText, TextRange(caret.coerceIn(0, newText.length))))
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            replaceAndEmit(text?.toString() ?: "", newCursorPosition, keepComposing = false)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            replaceAndEmit(text?.toString() ?: "", newCursorPosition, keepComposing = true)
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            val len = value.text.length
            setComposing(TextRange(start.coerceIn(0, len), end.coerceIn(0, len)))
            return true
        }

        override fun finishComposingText(): Boolean {
            setComposing(null)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val v = value
            val text = v.text
            val start = (v.selection.min - beforeLength).coerceAtLeast(0)
            val end = (v.selection.max + afterLength).coerceAtMost(text.length)
            val caret = start
            setComposing(null)
            emit(TextFieldValue(text.substring(0, start) + text.substring(end), TextRange(caret)))
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            deleteSurroundingText(beforeLength, afterLength)

        override fun setSelection(start: Int, end: Int): Boolean {
            val len = value.text.length
            emit(value.copy(selection = TextRange(start.coerceIn(0, len), end.coerceIn(0, len))))
            return true
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            if (event == null || event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    val v = value
                    if (!v.selection.collapsed) {
                        emit(TextFieldValue(v.text.substring(0, v.selection.min) + v.text.substring(v.selection.max), TextRange(v.selection.min)))
                    } else if (v.selection.min > 0) {
                        val s = v.selection.min
                        emit(TextFieldValue(v.text.substring(0, s - 1) + v.text.substring(s), TextRange(s - 1)))
                    }
                    return true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    replaceAndEmit("\n", 1, keepComposing = false)
                    return true
                }
                else -> {
                    val ch = event.unicodeChar
                    if (ch != 0) {
                        replaceAndEmit(ch.toChar().toString(), 1, keepComposing = false)
                        return true
                    }
                }
            }
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            replaceAndEmit("\n", 1, keepComposing = false)
            return true
        }

        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence {
            val v = value
            val end = v.selection.min
            val start = (end - length).coerceAtLeast(0)
            return v.text.substring(start, end)
        }

        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence {
            val v = value
            val start = v.selection.max
            val end = (start + length).coerceAtMost(v.text.length)
            return v.text.substring(start, end)
        }

        override fun getSelectedText(flags: Int): CharSequence? {
            val v = value
            if (v.selection.collapsed) return null
            return v.text.substring(v.selection.min, v.selection.max)
        }

        override fun getCursorCapsMode(reqModes: Int): Int = 0

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            val v = value
            return ExtractedText().apply {
                text = v.text
                selectionStart = v.selection.min
                selectionEnd = v.selection.max
                startOffset = 0
                partialStartOffset = -1
                partialEndOffset = -1
            }
        }

        override fun beginBatchEdit(): Boolean = true

        override fun endBatchEdit(): Boolean = false

        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    }
}
