package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stateful, observable editor over a single [OdfDocument.TextDocument] with its own undo/redo.
 * Used by the standalone markdown editor; Office keeps its own ViewModel-level state but shares the
 * pure transforms in [OdfRunEdits.kt] and [OdfParagraphOps.kt].
 */
class OdfTextEditorState(initial: OdfDocument.TextDocument) {
    private val _document = MutableStateFlow(initial)
    val document: StateFlow<OdfDocument.TextDocument> = _document

    val current: OdfDocument.TextDocument get() = _document.value

    private val undoStack = ArrayDeque<OdfDocument.TextDocument>()
    private val redoStack = ArrayDeque<OdfDocument.TextDocument>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo

    private fun commit(newDoc: OdfDocument.TextDocument?) {
        val nd = newDoc ?: return
        undoStack.addLast(_document.value)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        // Keep ordered-list numbering correct after every structural edit (indent, move, delete, …).
        _document.value = renumberLists(nd)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = false
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_document.value)
        _document.value = prev
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_document.value)
        _document.value = next
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    // --- continuous run editing ---
    fun updateParagraphRun(start: Int, endInclusive: Int, newText: String) =
        commit(current.updateParagraphRun(start, endInclusive, newText))

    fun applyRunSpanStyle(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, transform: (OdfSpan) -> OdfSpan) =
        commit(current.applyRunSpanStyle(start, endInclusive, gStart, gEnd, transform))

    fun runRangeHasFormat(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, predicate: (OdfSpan) -> Boolean): Boolean =
        current.runRangeHasFormat(start, endInclusive, gStart, gEnd, predicate)

    fun runParagraphIndexAt(start: Int, endInclusive: Int, gPos: Int): Int =
        current.runParagraphIndexAt(start, endInclusive, gPos)

    fun mutateRunParagraphs(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, transform: (OdfParagraph) -> OdfParagraph) =
        commit(current.mutateRunParagraphs(start, endInclusive, gStart, gEnd, transform))

    fun insertTextInRun(start: Int, endInclusive: Int, gPos: Int, insert: String) =
        commit(current.insertTextInRun(start, endInclusive, gPos, insert))

    fun clearRunFormatting(start: Int, endInclusive: Int, gStart: Int, gEnd: Int) =
        commit(current.clearRunFormatting(start, endInclusive, gStart, gEnd))

    // --- paragraph / list ops ---
    fun changeListLevel(blockIndex: Int, delta: Int) = commit(current.changeListLevel(blockIndex, delta))
    fun restartNumbering(blockIndex: Int) = commit(current.restartNumbering(blockIndex))
    fun insertHorizontalLine(blockIndex: Int) = commit(current.insertHorizontalLine(blockIndex))
    fun toggleListItem(blockIndex: Int) = commit(current.toggleListItem(blockIndex))
    fun toggleNumberedList(blockIndex: Int) = commit(current.toggleNumberedList(blockIndex))
    fun indentParagraph(blockIndex: Int) = commit(current.indentParagraph(blockIndex))
    fun outdentParagraph(blockIndex: Int) = commit(current.outdentParagraph(blockIndex))
    fun setParagraphStyle(blockIndex: Int, style: ParagraphStyle) = commit(current.setParagraphStyle(blockIndex, style))
    fun setParagraphAlignment(blockIndex: Int, alignment: TextAlign?) = commit(current.setParagraphAlignment(blockIndex, alignment))
    fun duplicateParagraph(blockIndex: Int) = commit(current.duplicateParagraph(blockIndex))
    fun moveParagraphUp(blockIndex: Int) = commit(current.moveParagraphUp(blockIndex))
    fun moveParagraphDown(blockIndex: Int) = commit(current.moveParagraphDown(blockIndex))

    fun toggleCheckbox(blockIndex: Int) = commit(current.toggleCheckbox(blockIndex))

    fun setCheckboxChecked(blockIndex: Int, checked: Boolean) = commit(current.setCheckboxChecked(blockIndex, checked))

    fun linkAt(start: Int, endInclusive: Int, gPos: Int): OdfLinkSpan? = current.linkAt(start, endInclusive, gPos)

    fun setLink(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, text: String, url: String) =
        commit(current.setLinkInRun(start, endInclusive, gStart, gEnd, text, url))

    /** Smart Enter; returns the new caret offset if it handled the keystroke, else null. */
    fun handleListEnter(start: Int, endInclusive: Int, gPos: Int): Int? {
        val (doc, caret) = current.handleListEnter(start, endInclusive, gPos) ?: return null
        commit(doc)
        return caret
    }

    /** Smart Backspace; returns the new caret offset if it handled the keystroke, else null. */
    fun handleListBackspace(start: Int, endInclusive: Int, gPos: Int): Int? {
        val (doc, caret) = current.handleListBackspace(start, endInclusive, gPos) ?: return null
        commit(doc)
        return caret
    }
    fun deleteParagraph(blockIndex: Int) = commit(current.deleteParagraph(blockIndex))

    /** Undo-history depth for [OdfTextEditorState]. */
    companion object {
        private const val MAX_UNDO = 30
    }
}
