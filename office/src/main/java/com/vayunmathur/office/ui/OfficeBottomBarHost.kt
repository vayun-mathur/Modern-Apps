package com.vayunmathur.office.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.ui.BottomBarActions
import com.vayunmathur.office.ui.DocCaps
import com.vayunmathur.office.ui.FormatTarget
import com.vayunmathur.office.ui.OfficeBottomBar
import com.vayunmathur.office.ui.ShapeKind
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.addShapeToSheet
import com.vayunmathur.office.platform.addShapeToSlide
import com.vayunmathur.office.platform.deleteSlideElement

import com.vayunmathur.office.MainActivity
import com.vayunmathur.office.platform.insertChart
import com.vayunmathur.office.platform.insertImage
import com.vayunmathur.office.platform.insertTable
/** Bottom bar (split from MainActivity.DocumentScreen). */

@Composable
internal fun DocumentBottomBarHost(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    canEdit: Boolean,
    isTextDoc: Boolean,
    isSpreadsheet: Boolean,
    isPresentation: Boolean,
    activeRunStart: Int,
    activeRunEnd: Int,
    selStart: Int,
    selEnd: Int,
    activeCell: Triple<Int, Int, Int>?,
    activeSlide: Int,
    activeSlideEl: Int,
    launchers: DocumentLaunchers,
    activeTableBlock: Int,
    activeTableRow: Int,
    activeTableCol: Int,
) {
    if (!canEdit) return
    val formatTarget = resolveFormatTarget(
        isTextDoc, isSpreadsheet, isPresentation,
        activeRunStart, activeRunEnd, selStart, selEnd, activeCell, activeSlide, activeSlideEl)
    val caps = resolveDocCaps(isTextDoc, isPresentation, isSpreadsheet)
    val actions = rememberBottomBarActions(
        s, viewModel, launchers, isPresentation, isSpreadsheet,
        activeSlide, activeCell, activeSlideEl)
    OfficeBottomBar(
        document,
        formatTarget,
        caps,
        viewModel,
        actions,
        activeTableBlock,
        activeTableRow,
        activeTableCol)
}

/** Format target from the active selection. */
private fun resolveFormatTarget(
    isTextDoc: Boolean,
    isSpreadsheet: Boolean,
    isPresentation: Boolean,
    activeRunStart: Int,
    activeRunEnd: Int,
    selStart: Int,
    selEnd: Int,
    activeCell: Triple<Int, Int, Int>?,
    activeSlide: Int,
    activeSlideEl: Int,
): FormatTarget = when {
    isTextDoc && activeRunStart >= 0 -> FormatTarget.TextRun(
        activeRunStart,
        activeRunEnd,
        selStart,
        selEnd)
    isSpreadsheet && (activeCell?.second ?: -1) >= 0 -> activeCell?.let {
        FormatTarget.Cell(it.first, it.second, it.third)
    } ?: FormatTarget.None
    isPresentation && activeSlideEl >= 0 -> FormatTarget.Element(activeSlide, activeSlideEl)
    else -> FormatTarget.None
}

/** Capability flags per document kind. */
private fun resolveDocCaps(
    isTextDoc: Boolean,
    isPresentation: Boolean,
    isSpreadsheet: Boolean,
): DocCaps = when {
    isTextDoc -> DocCaps(insertImage = true, insertChart = true, insertTable = true)
    isPresentation -> DocCaps(insertImage = true, insertShape = true, insertChart = true)
    isSpreadsheet -> DocCaps(insertImage = true, insertShape = true, insertChart = true)
    else -> DocCaps()
}

/** Bottom-bar callbacks. */
@Composable
private fun rememberBottomBarActions(
    s: DocumentScreenState,
    viewModel: OfficeViewModel,
    launchers: DocumentLaunchers,
    isPresentation: Boolean,
    isSpreadsheet: Boolean,
    activeSlide: Int,
    activeCell: Triple<Int, Int, Int>?,
    activeSlideEl: Int,
): BottomBarActions {
    val activeSheet = activeCell?.first ?: 0
    return BottomBarActions(
        onTextColor = { s.showColorPicker = true },
        onCellTextColor = { s.showCellTextColor = true },
        onCellBgColor = { s.showCellBgColor = true },
        onSlideTextColor = { s.showSlideTextColor = true },
        onSlideFill = { s.showSlideFillColor = true },
        onSlideStroke = { s.showSlideStrokeColor = true },
        onFontSize = { s.showFontSizePicker = true },
        onInsertImage = { launchers.imagePicker.launch("image/*") },
        onInsertShape = { kind ->
            insertShapeForDoc(viewModel, isPresentation, isSpreadsheet, activeSlide, activeSheet, kind)
        },
        onInsertChart = {
            s.editingChartBlock = -1
            s.chartForSlide = isPresentation
            s.chartForSheet = isSpreadsheet
            s.showChartEditor = true
        },
        onInsertTable = { s.showInsertTable = true },
        onDeleteElement = {
            if (activeSlideEl >= 0) {
                viewModel.deleteSlideElement(activeSlide, activeSlideEl)
            }
        },
        onCellBorder = { s.showCellBorderColor = true },
        onCellComment = { s.showCellComment = true },
        onCellResize = { s.showCellResize = true },
        onSlideNotes = { s.showSlideNotes = true },
        onSlideBackground = { s.showSlideBackground = true },
        onSlideTransition = { s.showSlideTransition = true }
    )
}

/** Inserts a shape into the active presentation/sheet. */
private fun insertShapeForDoc(
    viewModel: OfficeViewModel,
    isPresentation: Boolean,
    isSpreadsheet: Boolean,
    activeSlide: Int,
    activeSheet: Int,
    kind: ShapeKind,
) {
    when {
        isPresentation -> viewModel.addShapeToSlide(activeSlide, kind.name.lowercase())
        isSpreadsheet -> viewModel.addShapeToSheet(activeSheet, kind.name.lowercase())
    }
}
