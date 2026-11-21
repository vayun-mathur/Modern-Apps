package com.vayunmathur.office.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
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

/**
 * Keyboard focus traversal for the document editor: Tab moves to the next
 * interactive stop, Shift-Tab to the previous one, and Escape returns focus
 * to the document body. Stops follow the focus tree, which the editor
 * composes in visual order (top bar, menu bar, body segments in document
 * order, bottom toolbar), so moving focus with
 * [androidx.compose.ui.focus.FocusManager.moveFocus] walks them visually.
 *
 * Like [handleOfficeDocumentKey] this is key-down only and is suppressed
 * while a dialog or menu owns the keyboard ([dialogOpen], the same
 * `anyDialogOpen()` + open-menu guard the shortcuts use): Tab is swallowed
 * (consumed, no movement — otherwise Compose's default Tab handling would
 * move editor focus behind the overlay), while Escape is left unconsumed so
 * the overlay's own dismissal handling still wins. A callback
 * that declines (returns false — e.g. Escape while the body already has
 * focus, or no focusable stop in that direction) leaves the event
 * unconsumed so default handling still applies. Ctrl/Meta/Alt+Tab is left
 * to the window/OS (tab/app switching), never consumed here.
 */
internal fun handleOfficeFocusTraversalKey(
    event: KeyEvent,
    dialogOpen: Boolean,
    onNext: () -> Boolean,
    onPrevious: () -> Boolean,
    onEscapeToBody: () -> Boolean,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (dialogOpen) {
        return event.key == Key.Tab &&
            !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed
    }
    return when (event.key) {
        Key.Tab -> {
            if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) false
            else if (event.isShiftPressed) onPrevious() else onNext()
        }
        Key.Escape -> onEscapeToBody()
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
