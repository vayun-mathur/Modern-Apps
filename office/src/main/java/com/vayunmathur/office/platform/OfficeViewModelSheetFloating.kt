package com.vayunmathur.office.platform

import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.setElementBounds

/**
 * Sheet floating-element operations (split from OfficeViewModelSlides.kt for file length).
 * Behavior identical, call sites unchanged.
 */

// --- Spreadsheet cell comments & sizing ---

fun OfficeViewModel.setCellComment(sheetIndex: Int, rowIndex: Int, cellIndex: Int, author: String, text: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    modifyCell(doc, sheetIndex, rowIndex, cellIndex) {
        it.copy(annotation = if (text.isBlank()) null else OdfAnnotation(
            author = author.ifBlank { null },
            paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text))))
        ))
    }
}

/** Current comment text on a cell, or "". */
fun OfficeViewModel.cellCommentText(sheetIndex: Int, rowIndex: Int, cellIndex: Int): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return ""
    val cell = doc.sheets.getOrNull(sheetIndex)?.rows?.getOrNull(rowIndex)?.cells?.getOrNull(cellIndex) ?: return ""
    return cell.annotation?.paragraphs?.joinToString("\n") { p -> p.spans.joinToString("") { it.text } } ?: ""
}

/** Sets a column width in px@96 (null resets to default). */
fun OfficeViewModel.setColumnWidth(sheetIndex: Int, col: Int, widthPx: Float?) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val widths = sheet.columnWidths.toMutableList()
    while (widths.size <= col) widths.add(null)
    widths[col] = widthPx
    sheets[sheetIndex] = sheet.copy(columnWidths = widths)
    updateDocument(doc.copy(sheets = sheets))
}

/** Sets a row height in px@96 (null resets to default). */
fun OfficeViewModel.setRowHeight(sheetIndex: Int, row: Int, heightPx: Float?) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val heights = sheet.rowHeights.toMutableList()
    while (heights.size <= row) heights.add(null)
    heights[row] = heightPx
    sheets[sheetIndex] = sheet.copy(rowHeights = heights)
    updateDocument(doc.copy(sheets = sheets))
}

// --- Spreadsheet floating objects (Phase 4) ---

internal fun OfficeViewModel.mutateSheetFloating(
    sheetIndex: Int,
    transform: (List<OdfSlideElement>) -> List<OdfSlideElement>) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    sheets[sheetIndex] = sheet.copy(floating = transform(sheet.floating))
    updateDocument(doc.copy(sheets = sheets))
}

fun OfficeViewModel.addShapeToSheet(sheetIndex: Int, kind: String) {
    val x = 120f; val y = 120f; val w = 300f; val h = 200f
    val shape: OdfShape = when (kind) {
        "ellipse" -> OdfShape.Ellipse(x, y, w, h, fillColor = 0xFFB3D1FFL, strokeColor = 0xFF1F6FC0L, strokeWidth = 2f)
        "line" -> OdfShape.Line(x, y, w, 0f, strokeColor = 0xFF333333L, strokeWidth = 2f, x2 = x + w, y2 = y)
        else -> OdfShape.Rect(x, y, w, h, fillColor = 0xFFB3D1FFL, strokeColor = 0xFF1F6FC0L, strokeWidth = 2f)
    }
    mutateSheetFloating(sheetIndex) { it + OdfSlideElement.Shape(shape) }
}

fun OfficeViewModel.insertImageIntoSheet(sheetIndex: Int, fileName: String, bytes: ByteArray) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val path = uniqueImagePath(doc.images.keys, fileName)
    val frame = OdfFrame(x = 100f, y = 100f, width = 320f, height = 240f, paragraphs = emptyList(),
        image = OdfImage(path = path, imageData = bytes))
    sheets[sheetIndex] = sheet.copy(floating = sheet.floating + OdfSlideElement.Frame(frame))
    updateDocument(doc.copy(sheets = sheets, images = doc.images + (path to bytes)))
}

fun OfficeViewModel.insertChartIntoSheet(sheetIndex: Int, chart: OdfChart) {
    val frame = OdfFrame(x = 80f, y = 80f, width = 480f, height = 320f, paragraphs = emptyList(), chart = chart)
    mutateSheetFloating(sheetIndex) { it + OdfSlideElement.Frame(frame) }
}

fun OfficeViewModel.setSheetElementBounds(sheetIndex: Int, elementIndex: Int, x: Float, y: Float, w: Float, h: Float) {
    mutateSheetFloating(sheetIndex) { list ->
        if (elementIndex !in list.indices) list
        else list.toMutableList().also { it[elementIndex] = setElementBounds(it[elementIndex], x, y, w, h) }
    }
}

fun OfficeViewModel.deleteSheetElement(sheetIndex: Int, elementIndex: Int) {
    mutateSheetFloating(sheetIndex) { list -> list.filterIndexed { i, _ -> i != elementIndex } }
}

fun OfficeViewModel.updateSheetElementText(sheetIndex: Int, elementIndex: Int, newText: String) {
    mutateSheetFloating(sheetIndex) { list ->
        if (elementIndex !in list.indices) return@mutateSheetFloating list
        list.toMutableList().also { it[elementIndex] = setSlideElementTextOn(it[elementIndex], newText) }
    }
}
