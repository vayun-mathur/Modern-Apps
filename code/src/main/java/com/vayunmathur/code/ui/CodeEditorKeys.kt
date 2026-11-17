package com.vayunmathur.code.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.vayunmathur.code.domain.Edit
import com.vayunmathur.code.domain.EditorDocument
import com.vayunmathur.code.platform.dedentSelection
import com.vayunmathur.code.platform.indentSelection
/**
 * Handles an editor key press. Text edits go through [emit] (so smart input, undo/redo and
 * auto-save apply); multi-caret inserts/deletes are computed with an [EditorDocument]. Returns true
 * when the event was consumed.
 */
internal fun handleEditorKey(
    event: KeyEvent,
    value: TextFieldValue,
    tabWidth: Int,
    extraCarets: List<Int>,
    setExtraCarets: (List<Int>) -> Unit,
    emit: (TextFieldValue) -> Unit,
    move: (TextRange) -> Unit,
    onSave: () -> Unit,
    onComment: () -> Unit,
    foldAll: () -> Unit,
    unfoldAll: () -> Unit,
    toggleFoldAtCaret: (Int) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val handler = KeyHandler(value, tabWidth, extraCarets, setExtraCarets, emit, move)
    if (event.isCtrlPressed) return handler.handleCtrl(event, onSave, onComment, foldAll, unfoldAll, toggleFoldAtCaret)
    return handler.handlePlain(event)
}

private class KeyHandler(
    val value: TextFieldValue,
    val tabWidth: Int,
    val extraCarets: List<Int>,
    val setExtraCarets: (List<Int>) -> Unit,
    val emit: (TextFieldValue) -> Unit,
    val move: (TextRange) -> Unit,
) {
    val text = value.text
    val start = value.selection.min
    val end = value.selection.max

    fun handleCtrl(
        event: KeyEvent,
        onSave: () -> Unit,
        onComment: () -> Unit,
        foldAll: () -> Unit,
        unfoldAll: () -> Unit,
        toggleFoldAtCaret: (Int) -> Unit,
    ): Boolean {
        when (event.key) {
            Key.S -> runAction(onSave)
            Key.Slash -> runAction(onComment)
            Key.D -> addCaretBelow()
            Key.LeftBracket -> {
                if (event.isShiftPressed) foldAll() else toggleFoldAtCaret(start)
            }
            Key.RightBracket -> {
                if (event.isShiftPressed) unfoldAll() else toggleFoldAtCaret(start)
            }
            else -> return false
        }
        return true
    }

    private fun runAction(action: () -> Unit): Boolean {
        action()
        return true
    }

    private fun addCaretBelow() {
        val range = selectionOrWord(text, value.selection)
        if (range != null) {
            val word = text.substring(range.first, range.second)
            val from = (extraCarets + end).max()
            val idx = text.indexOf(word, from)
            if (idx >= 0) setExtraCarets(extraCarets + (idx + word.length))
        }
    }

    fun handlePlain(event: KeyEvent): Boolean {
        when (event.key) {
            Key.Escape -> return dismissExtraCarets()
            Key.Tab -> {
                val indented = if (event.isShiftPressed) {
                    dedentSelection(value, tabWidth)
                } else {
                    indentSelection(value, " ".repeat(tabWidth))
                }
                emit(indented)
                return true
            }
            Key.DirectionLeft -> move(TextRange((start - 1).coerceAtLeast(0)))
            Key.DirectionRight -> move(TextRange((end + 1).coerceAtMost(text.length)))
            Key.Backspace -> handleBackspace()
            Key.Enter, Key.NumPadEnter -> {
                emit(TextFieldValue(text.substring(0, start) + "\n" + text.substring(end), TextRange(start + 1)))
            }
            else -> return handleChar(event)
        }
        return true
    }

    private fun dismissExtraCarets(): Boolean {
        if (extraCarets.isEmpty()) return false
        setExtraCarets(emptyList())
        return true
    }

    private fun handleBackspace() {
        if (extraCarets.isNotEmpty()) {
            applyMultiCaret(text, start, extraCarets, insert = null, emit, setExtraCarets)
        } else if (start != end) {
            emit(TextFieldValue(text.substring(0, start) + text.substring(end), TextRange(start)))
        } else if (start > 0) {
            emit(TextFieldValue(text.substring(0, start - 1) + text.substring(start), TextRange(start - 1)))
        }
    }

    private fun handleChar(event: KeyEvent): Boolean {
        val codePoint = event.nativeKeyEvent.unicodeChar
        if (codePoint == 0) return false
        val ch = codePoint.toChar().toString()
        if (extraCarets.isNotEmpty()) {
            applyMultiCaret(text, start, extraCarets, insert = ch, emit, setExtraCarets)
        } else {
            emit(TextFieldValue(text.substring(0, start) + ch + text.substring(end), TextRange(start + 1)))
        }
        return true
    }
}

/**
 * Applies a single-character [insert] (or a backspace when null) at the primary caret plus every
 * extra caret, using an [EditorDocument] for the edit, then re-emits the primary value and the
 * shifted extra carets.
 */
private fun applyMultiCaret(
    text: String,
    primary: Int,
    extras: List<Int>,
    insert: String?,
    emit: (TextFieldValue) -> Unit,
    setExtraCarets: (List<Int>) -> Unit,
) {
    val carets = (extras + primary).distinct().sorted()
    val doc = EditorDocument(text)
    val edits = if (insert != null) {
        carets.map { Edit(it, it, insert) }
    } else {
        carets.filter { it > 0 }.map { Edit(it - 1, it, "") }
    }
    if (edits.isEmpty()) return
    doc.applyEdits(edits)
    val delta = if (insert != null) insert.length else -1
    val newCarets = carets.mapIndexed { index, c ->
        if (insert != null) c + delta * (index + 1) else (c + delta * (index + 1)).coerceAtLeast(0)
    }
    val primaryIndex = carets.indexOf(primary)
    val newPrimary = newCarets.getOrElse(primaryIndex) { newCarets.lastOrNull() ?: 0 }
    emit(TextFieldValue(doc.text, TextRange(newPrimary.coerceIn(0, doc.length))))
    setExtraCarets(newCarets.filterIndexed { i, _ -> i != primaryIndex }.map { it.coerceIn(0, doc.length) })
}

/** The selection range (end-exclusive) if non-empty, else the identifier word around the caret. */
private fun selectionOrWord(text: String, selection: TextRange): Pair<Int, Int>? {
    if (!selection.collapsed) return selection.min to selection.max
    var start = selection.start
    var end = selection.start
    while (start > 0 && text[start - 1].isJavaIdentifierChar()) start--
    while (end < text.length && text[end].isJavaIdentifierChar()) end++
    return if (end > start) start to end else null
}

private fun Char.isJavaIdentifierChar(): Boolean = isLetterOrDigit() || this == '_'
