package com.vayunmathur.office.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.ui.countChars
import com.vayunmathur.office.ui.countWords
import com.vayunmathur.office.ui.extractHeadings
import com.vayunmathur.office.ui.readingTimeMinutes
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.insertImage
import com.vayunmathur.office.platform.insertImageIntoSheet
import com.vayunmathur.office.platform.insertImageIntoSlide
import com.vayunmathur.office.platform.setLocalCaret

/** Value snapshot of the hoisted selection (avoids 9-param plumbing). */
internal class ScreenSelection(
    val activeRunStart: Int,
    val activeRunEnd: Int,
    val activeTableBlock: Int,
    val activeTableRow: Int,
    val activeTableCol: Int,
    val selStart: Int,
    val selEnd: Int,
    val activeCell: Triple<Int, Int, Int>?,
    val activeSlide: Int,
    val activeSlideEl: Int,
)

/** Mutable screen selection state (hoisted from DocumentScreen). */
internal class ScreenSelectionHolder(s: DocumentScreenState) {
    var showSearch by s.showSearchState
    var showUnsavedDialog by s.showUnsavedDialogState
    var activeRunStart by s.activeRunStartState
    var activeRunEnd by s.activeRunEndState
    var activeTableBlock by s.activeTableBlockState
    var activeTableRow by s.activeTableRowState
    var activeTableCol by s.activeTableColState
    var selStart by s.selStartState
    var selEnd by s.selEndState
    var activeCell by s.activeCellState
    var activeSlide by s.activeSlideState
    var activeSlideEl by s.activeSlideElState
}

/** Collected + derived document-screen chrome state. */
internal class DocumentChrome(
    val isEditMode: Boolean,
    val hasUnsavedChanges: Boolean,
    val isOnline: Boolean,
    val onlineEnabled: Boolean,
    val isSaving: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val nightMode: Boolean,
    val documentDarkMode: Boolean,
    val isTextDoc: Boolean,
    val isSpreadsheet: Boolean,
    val isPresentation: Boolean,
    val canEdit: Boolean,
    val saveAsName: String,
    val headings: List<com.vayunmathur.office.ui.HeadingItem>,
    val wordCount: Int,
    val charCount: Int,
    val readingTime: Int,
    val bookmarks: List<com.vayunmathur.library.ui.odf.OdfBookmark>,
)

/** Collects ViewModel flows + document-derived values for [DocumentScreen]. */
@Composable
internal fun rememberDocumentChrome(viewModel: OfficeViewModel, document: OdfDocument): DocumentChrome {
    val isEditMode by viewModel.isEditMode.collectAsState()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val onlineEnabled by viewModel.onlineEnabled.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    val documentThemeMode by viewModel.documentThemeMode.collectAsState()
    val documentDarkMode = when (documentThemeMode) {
        OfficeViewModel.DocumentThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        OfficeViewModel.DocumentThemeMode.UNCHANGED -> false
    }
    val isTextDoc = document is OdfDocument.TextDocument
    val isSpreadsheet = document is OdfDocument.Spreadsheet
    val isPresentation = document is OdfDocument.Presentation
    val saveAsName = document.title.substringBeforeLast('.').ifBlank { "Untitled" } +
        when { isTextDoc -> ".odt"; isSpreadsheet -> ".ods"; isPresentation -> ".odp"; else -> ".odg" }
    return DocumentChrome(
        isEditMode = isEditMode,
        hasUnsavedChanges = hasUnsavedChanges,
        isOnline = isOnline,
        onlineEnabled = onlineEnabled,
        isSaving = isSaving,
        canUndo = canUndo,
        canRedo = canRedo,
        nightMode = nightMode,
        documentDarkMode = documentDarkMode,
        isTextDoc = isTextDoc,
        isSpreadsheet = isSpreadsheet,
        isPresentation = isPresentation,
        canEdit = isTextDoc || isSpreadsheet || isPresentation,
        saveAsName = saveAsName,
        headings = remember(document) {
            if (document is OdfDocument.TextDocument) extractHeadings(document) else emptyList()
        },
        wordCount = remember(document) {
            if (document is OdfDocument.TextDocument) countWords(document) else 0
        },
        charCount = remember(document) {
            if (document is OdfDocument.TextDocument) countChars(document) else 0
        },
        readingTime = remember(document) {
            if (document is OdfDocument.TextDocument) readingTimeMinutes(document) else 0
        },
        bookmarks = remember(document) {
            if (document is OdfDocument.TextDocument) document.bookmarks else emptyList()
        },
    )
}

/** Remembered screen selection holder. */
@Composable
internal fun rememberScreenSelection(s: DocumentScreenState): ScreenSelectionHolder {
    return remember(s) { ScreenSelectionHolder(s) }
}

/** Run-selection handler (clears table selection, moves the caret). */
@Composable
internal fun rememberRunSelectionHandler(
    sel: ScreenSelectionHolder,
    viewModel: OfficeViewModel,
): (Int, Int, Int, Int) -> Unit = remember(sel, viewModel) {
    { rs: Int, re: Int, gs: Int, ge: Int ->
        sel.activeRunStart = rs
        sel.activeRunEnd = re
        sel.selStart = gs
        sel.selEnd = ge
        sel.activeTableBlock = -1
        sel.activeTableRow = -1
        sel.activeTableCol = -1
        viewModel.setLocalCaret(gs)
    }
}

/** Image-picker handler routing bytes to the active document kind. */
@Composable
internal fun rememberImagePickerHandler(
    document: OdfDocument,
    viewModel: OfficeViewModel,
    focusedPara: Int,
    activeSlide: Int,
    activeCell: Triple<Int, Int, Int>?,
): (String, ByteArray) -> Unit = remember(document, focusedPara, activeSlide, activeCell) {
    { name, bytes ->
        when (document) {
            is OdfDocument.TextDocument -> if (focusedPara >= 0) viewModel.insertImage(focusedPara, name, bytes)
            is OdfDocument.Presentation -> viewModel.insertImageIntoSlide(activeSlide, name, bytes)
            is OdfDocument.Spreadsheet -> viewModel.insertImageIntoSheet(activeCell?.first ?: 0, name, bytes)
            else -> {}
        }
    }
}
