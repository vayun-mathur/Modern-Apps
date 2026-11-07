package com.vayunmathur.code.util

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.vayunmathur.code.syntax.Language
import java.io.File
import java.nio.charset.Charset

/** One expandable row in the file-tree pane; the tree is stored as a flat, ordered list. */
class TreeNode(val entry: FileEntry, val depth: Int) {
    var expanded by mutableStateOf(false)
    var loading by mutableStateOf(false)
}

/**
 * One open file. Editor content lives in [value]; [savedText] is the last persisted text so
 * [isDirty] can drive the unsaved-dot. Undo/redo are plain deques (not observed directly);
 * [canUndo]/[canRedo] mirror their emptiness as state so the toolbar buttons stay reactive.
 *
 * Most tabs are backed by a real [file]. Files opened through a VIEW/EDIT intent from another
 * app arrive as a `content://` [externalUri] instead and are [readOnly] (no `save` target).
 */
class OpenTab(
    file: File?,
    externalUri: Uri? = null,
    val readOnly: Boolean = false,
    initialName: String,
    initialText: String,
    language: Language,
) {
    var file by mutableStateOf(file)
    var externalUri by mutableStateOf(externalUri)
    var name by mutableStateOf(initialName)
    var language by mutableStateOf(language)
    var value by mutableStateOf(TextFieldValue(initialText))
    var savedText by mutableStateOf(initialText)

    /** Fold header lines currently collapsed in this tab (persisted per file path). */
    var foldedHeaders by mutableStateOf<Set<Int>>(emptySet())
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    // Fidelity metadata so a save round-trips the file byte-for-byte (see [TextEncoding]).
    var charset: Charset = Charsets.UTF_8
    var lineEnding: LineEnding = LineEnding.LF
    var hadBom: Boolean = false

    // Snapshot of the file on disk when it was last opened/saved, for external-change detection.
    var diskModified: Long = 0L
    var diskLength: Long = 0L

    /** Set when the file changed on disk while this tab held unsaved edits (drives the banner). */
    var changedOnDisk by mutableStateOf(false)

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    /** Stable identity used to dedup tabs: the file path, or the external URI string. */
    val key: String get() = file?.absolutePath ?: externalUri.toString()

    val isDirty: Boolean get() = value.text != savedText

    fun pushUndo(previous: TextFieldValue) {
        undoStack.addLast(previous)
        if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        canUndo = true
        canRedo = false
    }

    /** Drops all undo/redo history (used when the buffer is replaced wholesale, e.g. a reload). */
    fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        canUndo = false
        canRedo = false
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(value)
        value = previous
        canUndo = undoStack.isNotEmpty()
        canRedo = true
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(value)
        value = next
        canRedo = redoStack.isNotEmpty()
        canUndo = true
    }

    private companion object {
        const val UNDO_LIMIT = 100
    }
}

/** Decoded bytes of a file plus its on-disk snapshot; safe default on read failure. */
internal class LoadedFile(val decoded: DecodedText, val modified: Long, val length: Long)
