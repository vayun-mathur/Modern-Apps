package com.vayunmathur.code.domain
/** A single replacement: swap the half-open range `[start, end)` for [text]. */
data class Edit(val start: Int, val end: Int, val text: String)

/**
 * A line-indexed text buffer for the virtualized editor engine. Pure (no Android dependencies) and
 * unit-tested. Provides fast line ↔ offset mapping (cached, rebuilt on edit), single [replace] edits,
 * multi-caret [applyEdits] applied right-to-left so earlier offsets stay valid, and grouped
 * undo/redo mirroring the per-tab deque semantics of the old editor.
 */
class EditorDocument(initial: String = "") {

    private val builder = StringBuilder(initial)
    private var cachedLineStarts: IntArray? = null

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    val text: String get() = builder.toString()
    val length: Int get() = builder.length

    /** Offsets where each line begins; always starts with 0. Rebuilt lazily after edits. */
    private fun lineStarts(): IntArray {
        cachedLineStarts?.let { return it }
        val starts = ArrayList<Int>()
        starts.add(0)
        for (i in 0 until builder.length) {
            if (builder[i] == '\n') starts.add(i + 1)
        }
        return starts.toIntArray().also { cachedLineStarts = it }
    }

    fun lineCount(): Int = lineStarts().size

    /** 0-based line → offset of its first character. */
    fun lineStart(line: Int): Int {
        val starts = lineStarts()
        return starts[line.coerceIn(0, starts.size - 1)]
    }

    /** 0-based line → offset just past its last character, excluding the trailing newline. */
    fun lineEnd(line: Int): Int {
        val starts = lineStarts()
        val clamped = line.coerceIn(0, starts.size - 1)
        return if (clamped + 1 < starts.size) starts[clamped + 1] - 1 else builder.length
    }

    fun lineText(line: Int): String = builder.substring(lineStart(line), lineEnd(line))

    /** The 0-based line containing [offset] (binary search over the line-start table). */
    fun lineOfOffset(offset: Int): Int {
        val target = offset.coerceIn(0, builder.length)
        val starts = lineStarts()
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= target) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun columnOfOffset(offset: Int): Int = offset.coerceIn(0, builder.length) - lineStart(lineOfOffset(offset))

    fun offsetOf(line: Int, column: Int): Int {
        val start = lineStart(line)
        val end = lineEnd(line)
        return (start + column).coerceIn(start, end)
    }

    /** Replaces the half-open range `[start, end)` with [newText] as one undo step. */
    fun replace(start: Int, end: Int, newText: String) {
        applyEdits(listOf(Edit(start, end, newText)))
    }

    /**
     * Applies several non-overlapping [edits] as a single grouped undo step. Edits are sorted and
     * applied from the highest offset down so that earlier offsets remain valid as the text mutates.
     */
    fun applyEdits(edits: List<Edit>) {
        if (edits.isEmpty()) return
        pushUndo()
        val ordered = edits.sortedByDescending { it.start }
        for (edit in ordered) {
            val s = edit.start.coerceIn(0, builder.length)
            val e = edit.end.coerceIn(s, builder.length)
            builder.replace(s, e, edit.text)
        }
        cachedLineStarts = null
    }

    private fun pushUndo() {
        undoStack.addLast(builder.toString())
        if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        redoStack.clear()
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(builder.toString())
        setTextInternal(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(builder.toString())
        setTextInternal(next)
    }

    private fun setTextInternal(value: String) {
        builder.setLength(0)
        builder.append(value)
        cachedLineStarts = null
    }

    private companion object {
        const val UNDO_LIMIT = 100
    }
}
