package com.vayunmathur.office.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Desktop keyboard shortcuts for the document editor, following the `code` editor's
 * `handleEditorKey` pattern: only key-down events, triggered by Ctrl or Meta (the
 * latter is Cmd on macOS-style keyboards / the super key in desktop windows), and
 * never while a dialog or menu overlay is open — a modal owns its keys.
 *
 * Each action returns true when it actually did something (e.g. a formatting
 * target is selected); a shortcut whose action declines is left unconsumed so the
 * focused field keeps its default handling.
 */
internal fun handleOfficeDocumentKey(
    event: KeyEvent,
    dialogOpen: Boolean,
    onSave: () -> Boolean,
    onToggleBold: () -> Boolean,
    onToggleItalic: () -> Boolean,
): Boolean {
    if (event.type != KeyEventType.KeyDown || dialogOpen) return false
    if (!event.isCtrlPressed && !event.isMetaPressed) return false
    return when (event.key) {
        Key.S -> onSave()
        Key.B -> onToggleBold()
        Key.I -> onToggleItalic()
        else -> false
    }
}

/** True while any hoisted dialog/crop/export overlay of the document screen is showing. */
internal fun DocumentOverlayState.anyDialogOpen(): Boolean =
    showMetadata || showShareDialog || showEnableOnlineDialog || showUnsavedDialog ||
        showSettings || showColorPicker || showFontSizePicker || showInsertTable ||
        showInsertLink || showAddBookmark || showSpecialChars || showFootnote ||
        showComment || showComments || showChanges || showPageSetup || showHeaderFooter ||
        showCellTextColor || showCellBgColor || showCellBorderColor ||
        showSlideTextColor || showSlideFillColor || showSlideStrokeColor ||
        showCellComment || showCellResize || showSlideNotes || showSlideBackground ||
        showSlideTransition || showChartEditor ||
        cropImageBlock >= 0 || cropSlideTarget != null || cropSheetTarget != null ||
        exportWarning != null
