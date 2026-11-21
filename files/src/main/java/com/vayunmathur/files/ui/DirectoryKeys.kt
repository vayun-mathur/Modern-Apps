package com.vayunmathur.files.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Desktop keyboard shortcuts for the directory browser, following the `code`
 * editor's key-handler pattern: only key-down events, Ctrl or Meta as the
 * shortcut modifier (Cmd on macOS-style keyboards), and never while a dialog
 * is open — a modal owns its keys.
 *
 * Escape unwinds one layer at a time, in the same order as Back: an active
 * search is cleared first (deactivating search also clears its query), then
 * any selection. Ctrl+A is deliberately not consumed while search is active:
 * the focused search field keeps its own select-all-the-query behaviour.
 */
internal fun handleDirectoryKey(
    event: KeyEvent,
    dialogOpen: Boolean,
    isSearchActive: Boolean,
    hasSelection: Boolean,
    onFocusSearch: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSearch: () -> Unit,
    onClearSelection: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || dialogOpen) return false
    if (event.key == Key.Escape) {
        return when {
            isSearchActive -> {
                onClearSearch()
                true
            }
            hasSelection -> {
                onClearSelection()
                true
            }
            else -> false
        }
    }
    if (!event.isCtrlPressed && !event.isMetaPressed) return false
    return when (event.key) {
        Key.F, Key.L -> {
            onFocusSearch()
            true
        }
        Key.A -> {
            if (isSearchActive) {
                false
            } else {
                onSelectAll()
                true
            }
        }
        else -> false
    }
}
