package com.vayunmathur.photos.domain

/**
 * Generic, bounded undo/redo history. The caller owns the "current" state; this
 * manager only stores past/future snapshots.
 *
 * Typical usage with [com.vayunmathur.photos.data.EditDocument]:
 * ```
 * // before mutating, record the state we are leaving:
 * history.push(currentDocument)
 * currentDocument = mutated
 *
 * // undo: hand in the current state, get back the previous one (or null):
 * history.undo(currentDocument)?.let { currentDocument = it }
 * ```
 */
class UndoRedoManager<T>(private val capacity: Int = 30) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Records [state] as a restorable snapshot and clears the redo history. */
    fun push(state: T) {
        undoStack.addLast(state)
        while (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
    }

    /**
     * Returns the previous snapshot, moving [current] onto the redo stack.
     * Returns null when there is nothing to undo.
     */
    fun undo(current: T): T? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        while (redoStack.size > capacity) redoStack.removeFirst()
        return previous
    }

    /**
     * Returns the next snapshot, moving [current] back onto the undo stack.
     * Returns null when there is nothing to redo.
     */
    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        while (undoStack.size > capacity) undoStack.removeFirst()
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
