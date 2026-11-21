package com.vayunmathur.office.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.ui.DrawingView
import com.vayunmathur.office.ui.PresentationView
import com.vayunmathur.office.ui.SpreadsheetView
import com.vayunmathur.office.ui.TextDocumentView
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.addColumn
import com.vayunmathur.office.platform.addRow
import com.vayunmathur.office.platform.addSheet
import com.vayunmathur.office.platform.addSlide
import com.vayunmathur.office.platform.addTextBoxToSlide
import com.vayunmathur.office.platform.deleteBlockBefore
import com.vayunmathur.office.platform.deleteColumn
import com.vayunmathur.office.platform.deleteRow
import com.vayunmathur.office.platform.deleteSheet
import com.vayunmathur.office.platform.deleteSheetElement
import com.vayunmathur.office.platform.deleteSlide
import com.vayunmathur.office.platform.deleteSlideElement
import com.vayunmathur.office.platform.duplicateSlide
import com.vayunmathur.office.platform.handleListBackspace
import com.vayunmathur.office.platform.handleListEnter
import com.vayunmathur.office.platform.mergeCells
import com.vayunmathur.office.platform.moveSlideDown
import com.vayunmathur.office.platform.moveSlideUp
import com.vayunmathur.office.platform.setCellAlignment
import com.vayunmathur.office.platform.setCellBgColor
import com.vayunmathur.office.platform.setCellBold
import com.vayunmathur.office.platform.setCellColor
import com.vayunmathur.office.platform.setCellItalic
import com.vayunmathur.office.platform.setCheckboxChecked
import com.vayunmathur.office.platform.setLocalLocation
import com.vayunmathur.office.platform.setSheetElementBounds
import com.vayunmathur.office.platform.setSheetFreeze
import com.vayunmathur.office.platform.setSlideElementBounds
import com.vayunmathur.office.platform.sortRows
import com.vayunmathur.office.platform.unmergeCells
import com.vayunmathur.office.platform.updateCellText
import com.vayunmathur.office.platform.updateParagraphRun
import com.vayunmathur.office.platform.updateSheetElementText
import com.vayunmathur.office.platform.updateSlideElementText
import com.vayunmathur.office.platform.updateTextTableCell

import com.vayunmathur.office.platform.OfficePresence
import com.vayunmathur.office.platform.save
import com.vayunmathur.office.R
import com.vayunmathur.office.platform.renameSheet
/**
 * Document pane shared by the single-pane body (compact) and the wide layout (Expanded).
 *
 * Hoisted verbatim from `DocumentScreen`'s local `DocumentPane` (split for file length);
 * captures became explicit parameters, behavior identical.
 */
private const val NIGHT_SCRIM = 0x660E1116L

@Composable
fun OfficeDocumentPane(
    document: OdfDocument,
    viewModel: OfficeViewModel,
    documentDarkMode: Boolean,
    nightMode: Boolean,
    searchQuery: String,
    fontSizeMultiplier: Float,
    listState: LazyListState,
    isEditMode: Boolean,
    activeSlideEl: Int,
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit,
    onCellFocus: (Int, Int, Int) -> Unit,
    onCellSelected: (Int, Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onSlideElementSelected: (Int, Int) -> Unit,
    onChartClick: (Int) -> Unit,
    onCropImage: (Int) -> Unit,
    onCropSlide: (Int, Int) -> Unit,
    onCropSheet: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    bodyFocusRequester: FocusRequester? = null,
    onBodyFocusChanged: (Boolean) -> Unit = {},
) {
    Box(modifier) {
        val presence by viewModel.remotePresence.collectAsState()
        PresenceBanner(presence)
        OfficeLightTheme {
          Surface(
            Modifier
                .fillMaxSize()
                .then(if (documentDarkMode) Modifier.documentDarkModeInvert() else Modifier),
            color = MaterialTheme.colorScheme.background,
          ) {
        when (document) {
            is OdfDocument.TextDocument -> TextPane(
                document, viewModel, searchQuery, fontSizeMultiplier, listState,
                presence, onRunSelectionChange, onCellFocus, onChartClick, onCropImage,
                bodyFocusRequester, onBodyFocusChanged)
            is OdfDocument.Spreadsheet -> SheetPane(
                document, viewModel, searchQuery, fontSizeMultiplier, isEditMode,
                onCellSelected, onCropSheet)
            is OdfDocument.Presentation -> SlidePane(
                document, viewModel, isEditMode, activeSlideEl,
                onSlideChange, onSlideElementSelected, onCropSlide)
            is OdfDocument.Drawing -> DrawingView(document)
        }
          }
        }
        // Night reading mode: a view-only dimming scrim (does not modify or save the document). (C4)
        if (nightMode) Box(Modifier.matchParentSize().background(Color(NIGHT_SCRIM)))
    }
}

/** Remote-presence banner. */
@Composable
private fun PresenceBanner(
    presence: List<com.vayunmathur.office.platform.OfficePresence>,
) {
    if (presence.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.zIndex(1f).fillMaxWidth()
    ) {
        val typingLabel = stringResource(R.string.typing)
        Text(
            presence.joinToString(", ") { p ->
                p.name + (p.loc?.let { l -> " · $l" } ?: "") + if (p.typing) typingLabel else ""
            }
                .let { stringResource(R.string.online_1, it) },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

/** Text-document pane. */
@Composable
private fun TextPane(
    document: OdfDocument.TextDocument,
    viewModel: OfficeViewModel,
    searchQuery: String,
    fontSizeMultiplier: Float,
    listState: LazyListState,
    presence: List<com.vayunmathur.office.platform.OfficePresence>,
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit,
    onCellFocus: (Int, Int, Int) -> Unit,
    onChartClick: (Int) -> Unit,
    onCropImage: (Int) -> Unit,
    bodyFocusRequester: FocusRequester? = null,
    onBodyFocusChanged: (Boolean) -> Unit = {},
) {
    val remoteCarets = presence.mapNotNull { p ->
        p.caret?.let { com.vayunmathur.library.ui.odf.RemoteCaret(
            it,
            0xFF000000L or (p.id.hashCode().toLong() and 0xFFFFFFL),
            p.name) }
    }
    TextDocumentView(
        doc = document,
        searchQuery = searchQuery,
        fontSizeMultiplier = fontSizeMultiplier,
        listState = listState,
        remoteCarets = remoteCarets,
        onRunSelectionChange = { rs, re, gs, ge -> onRunSelectionChange(rs, re, gs, ge) },
        onRunTextChange = { rs, re, text -> viewModel.updateParagraphRun(rs, re, text) },
        onRunEnter = { rs, re, gPos -> viewModel.handleListEnter(rs, re, gPos) },
        onRunBackspace = { rs, re, gPos -> viewModel.handleListBackspace(rs, re, gPos) },
        onToggleCheckbox = { idx ->
            val p = (document.content.getOrNull(idx) as? OdfContentBlock.Paragraph)?.paragraph
            if (p != null) viewModel.setCheckboxChecked(idx, !p.listChecked)
        },
        onDeletePrevBlock = { runStart -> viewModel.deleteBlockBefore(runStart) },
        onCellTextChange = { bi, r, c, text -> viewModel.updateTextTableCell(bi, r, c, text) },
        onCellFocus = { bi, r, c -> onCellFocus(bi, r, c) },
        onChartClick = { bi -> onChartClick(bi) },
        onCropImage = { bi -> onCropImage(bi) },
        bodyFocusRequester = bodyFocusRequester,
        onBodyFocusChanged = onBodyFocusChanged)
}

/** Spreadsheet pane. */
@Composable
private fun SheetPane(
    document: OdfDocument.Spreadsheet,
    viewModel: OfficeViewModel,
    searchQuery: String,
    fontSizeMultiplier: Float,
    isEditMode: Boolean,
    onCellSelected: (Int, Int, Int) -> Unit,
    onCropSheet: (Int, Int) -> Unit,
) {
    SpreadsheetView(
        doc = document,
        searchQuery = searchQuery,
        fontSizeMultiplier = fontSizeMultiplier,
        isEditMode = isEditMode,
        onCellTextChange = { s, r, c, t -> viewModel.updateCellText(s, r, c, t) },
        onAddRow = { s, r -> viewModel.addRow(s, r) },
        onAddColumn = { s -> viewModel.addColumn(s) },
        onDeleteRow = { s, r -> viewModel.deleteRow(s, r) },
        onDeleteColumn = { s, c -> viewModel.deleteColumn(s, c) },
        onRenameSheet = { s, n -> viewModel.renameSheet(s, n) },
        onAddSheet = { viewModel.addSheet() },
        onDeleteSheet = { s -> viewModel.deleteSheet(s) },
        onSort = { s, col, asc -> viewModel.sortRows(s, col, asc) },
        onCellSelected = { s, r, c ->
            onCellSelected(s, r, c)
            viewModel.setLocalLocation("Sheet ${s + 1} · ${('A' + c)}${r + 1}")
        },
        onFloatingBoundsChange = { s, e, x, y, w, h -> viewModel.setSheetElementBounds(s, e, x, y, w, h) },
        onFloatingTextChange = { s, e, t -> viewModel.updateSheetElementText(s, e, t) },
        onFloatingDelete = { s, e -> viewModel.deleteSheetElement(s, e) },
        onFloatingCrop = { s, e -> onCropSheet(s, e) },
        onSetFreeze = { s, r, c -> viewModel.setSheetFreeze(s, r, c) })
}

/** Presentation pane. */
@Composable
private fun SlidePane(
    document: OdfDocument.Presentation,
    viewModel: OfficeViewModel,
    isEditMode: Boolean,
    activeSlideEl: Int,
    onSlideChange: (Int) -> Unit,
    onSlideElementSelected: (Int, Int) -> Unit,
    onCropSlide: (Int, Int) -> Unit,
) {
    PresentationView(
        doc = document, isEditMode = isEditMode,
        onAddSlide = { viewModel.addSlide(it) },
        onDeleteSlide = { viewModel.deleteSlide(it) },
        onDuplicateSlide = { viewModel.duplicateSlide(it) },
        onMoveSlideUp = { viewModel.moveSlideUp(it) },
        onMoveSlideDown = { viewModel.moveSlideDown(it) },
        onElementTextChange = { s, e, t -> viewModel.updateSlideElementText(s, e, t) },
        onAddTextBox = { viewModel.addTextBoxToSlide(it) },
        onElementBoundsChange = { s, e, x, y, w, h -> viewModel.setSlideElementBounds(s, e, x, y, w, h) },
        onDeleteElement = { s, e -> viewModel.deleteSlideElement(s, e) },
        selectedElement = activeSlideEl,
        onSlideChange = { onSlideChange(it); viewModel.setLocalLocation("Slide ${it + 1}") },
        onElementSelected = { s, e -> onSlideElementSelected(s, e) },
        onCropImage = { s, e -> onCropSlide(s, e) })
}
