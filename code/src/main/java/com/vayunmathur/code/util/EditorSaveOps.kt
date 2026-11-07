package com.vayunmathur.code.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal const val AUTO_SAVE_DELAY_MS = 1500L

/** Debounced auto-save: writes the current tab a short idle period after the last edit. */
internal fun EditorViewModel.scheduleAutoSave() {
    autoSaveJob?.cancel()
    autoSaveJob = viewModelScope.launch {
        delay(AUTO_SAVE_DELAY_MS)
        val tab = activeTab ?: return@launch
        if (tab.isDirty) saveTab(tab)
    }
}

/** Encodes [tab] with its stored charset/BOM/line-ending and writes it, refreshing the snapshot. */
internal fun EditorViewModel.saveTab(tab: OpenTab) {
    val file = tab.file ?: return // external read-only tabs have no save target
    var textToSave = tab.value.text
    if (trimTrailingOnSave) textToSave = trimTrailingWhitespace(textToSave)
    if (finalNewlineOnSave) textToSave = ensureFinalNewline(textToSave)
    // Reflect the transformed text back into the buffer so the tab isn't left "dirty".
    if (textToSave != tab.value.text) {
        val sel = tab.value.selection
        val clamped = TextRange(
            sel.start.coerceAtMost(textToSave.length),
            sel.end.coerceAtMost(textToSave.length),
        )
        tab.value = TextFieldValue(textToSave, clamped)
    }
    val bytes = TextEncoding.encode(textToSave, tab.charset, tab.lineEnding, tab.hadBom)
    viewModelScope.launch {
        // NonCancellable: a save the user asked for (e.g. Save-all in the exit guard, right
        // before the Activity finishes and this scope is cancelled) must not be abandoned
        // mid-write. The write itself is atomic (see FileFiles.writeBytes).
        val ok = withContext(Dispatchers.IO + NonCancellable) {
            runCatching { FileFiles.writeBytes(file, bytes) }.isSuccess
        }
        if (ok) {
            tab.savedText = textToSave
            tab.changedOnDisk = false
            val (modified, length) = withContext(Dispatchers.IO + NonCancellable) {
                file.lastModified() to file.length()
            }
            tab.diskModified = modified
            tab.diskLength = length
            if (gitIsRepo) refreshGit()
        }
    }
}

/**
 * Compares each open file-backed tab against its on-disk snapshot (called on ON_RESUME and after
 * git actions). A clean tab is silently reloaded; a dirty tab raises its "changed on disk" banner.
 */
fun EditorViewModel.checkExternalChanges() {
    if (tabs.isEmpty()) return
    viewModelScope.launch {
        for (tab in tabs.toList()) checkTab(tab)
    }
}

private suspend fun EditorViewModel.checkTab(tab: OpenTab) {
    val file = tab.file ?: return
    val snapshot = withContext(Dispatchers.IO) {
        if (file.exists()) file.lastModified() to file.length() else null
    } ?: return
    if (snapshot.first == tab.diskModified && snapshot.second == tab.diskLength) return
    if (tab.isDirty) tab.changedOnDisk = true else applyReload(tab)
}

/** Replaces [tab]'s buffer with the current disk contents and refreshes its snapshot. */
internal suspend fun EditorViewModel.applyReload(tab: OpenTab) {
    val file = tab.file ?: return
    // A failed read must not wipe the buffer: keep the current text and snapshot untouched.
    val loaded = loadFile(file) ?: return
    tab.value = TextFieldValue(loaded.decoded.text)
    tab.savedText = loaded.decoded.text
    tab.charset = loaded.decoded.charset
    tab.lineEnding = loaded.decoded.lineEnding
    tab.hadBom = loaded.decoded.hadBom
    tab.diskModified = loaded.modified
    tab.diskLength = loaded.length
    tab.changedOnDisk = false
    scheduleDiagnostics()
}
