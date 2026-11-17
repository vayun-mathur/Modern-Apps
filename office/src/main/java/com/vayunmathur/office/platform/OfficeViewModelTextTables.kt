package com.vayunmathur.office.platform

import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow

/**
 * In-text table operations (split from OfficeViewModelTables.kt for file length).
 * Behavior identical, call sites unchanged.
 */

internal fun OfficeViewModel.withTextTable(blockIndex: Int, transform: (OdfTable) -> OdfTable) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Table ?: return
    val newContent = doc.content.toMutableList()
    newContent[blockIndex] = OdfContentBlock.Table(transform(block.table))
    updateDocument(doc.copy(content = newContent))
}

fun OfficeViewModel.updateTextTableCell(
    blockIndex: Int,
    row: Int,
    col: Int,
    newText: String) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val r = rows.getOrNull(row) ?: return@withTextTable table
    val cells = r.cells.toMutableList()
    if (col !in cells.indices) return@withTextTable table
    val paras = newText.split("\n").map { OdfParagraph(listOf(OdfSpan(text = it))) }
    cells[col] = cells[col].copy(paragraphs = paras)
    rows[row] = OdfTableRow(cells)
    table.copy(rows = rows)
}

fun OfficeViewModel.textTableAddRow(blockIndex: Int, afterRow: Int) = withTextTable(blockIndex) { table ->
    val colCount = table.rows.firstOrNull()?.cells?.size ?: 1
    val newRow =
        OdfTableRow(List(colCount) { OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = ""))))) })
    val rows = table.rows.toMutableList()
    val at = (afterRow + 1).coerceIn(0, rows.size)
    rows.add(at, newRow)
    table.copy(rows = rows)
}

fun OfficeViewModel.textTableAddColumn(blockIndex: Int, afterCol: Int) = withTextTable(blockIndex) { table ->
    table.copy(rows = table.rows.map { row ->
        val cells = row.cells.toMutableList()
        val at = (afterCol + 1).coerceIn(0, cells.size)
        cells.add(at, OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = ""))))))
        OdfTableRow(cells)
    })
}

fun OfficeViewModel.textTableDeleteRow(blockIndex: Int, row: Int) = withTextTable(blockIndex) { table ->
    if (table.rows.size <= 1 || row !in table.rows.indices) table
    else table.copy(rows = table.rows.toMutableList().apply { removeAt(row) })
}

fun OfficeViewModel.textTableDeleteColumn(blockIndex: Int, col: Int) = withTextTable(blockIndex) { table ->
    table.copy(rows = table.rows.map { row ->
        if (row.cells.size <= 1 || col !in row.cells.indices) row
        else OdfTableRow(row.cells.toMutableList().apply { removeAt(col) })
    })
}

/** Per-cell character formatting for text-document tables (C26). */
fun OfficeViewModel.setTextTableCellSpanFormat(
    blockIndex: Int,
    row: Int,
    col: Int,
    transform: (OdfSpan) -> OdfSpan) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val r = rows.getOrNull(row) ?: return@withTextTable table
    val cells = r.cells.toMutableList()
    val cell = cells.getOrNull(col) ?: return@withTextTable table
    val newParas = cell.paragraphs.map { p -> p.copy(spans = p.spans.map(transform)) }
    cells[col] = cell.copy(paragraphs = newParas)
    rows[row] = OdfTableRow(cells)
    table.copy(rows = rows)
}

fun OfficeViewModel.setTextTableCellAlignment(
    blockIndex: Int,
    row: Int,
    col: Int,
    alignment: androidx.compose.ui.text.style.TextAlign?) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val r = rows.getOrNull(row) ?: return@withTextTable table
    val cells = r.cells.toMutableList()
    val cell = cells.getOrNull(col) ?: return@withTextTable table
    cells[col] = cell.copy(paragraphs = cell.paragraphs.map { it.copy(alignment = alignment) })
    rows[row] = OdfTableRow(cells)
    table.copy(rows = rows)
}

fun OfficeViewModel.setTextTableCellBackground(
    blockIndex: Int,
    row: Int,
    col: Int,
    color: Long?) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val r = rows.getOrNull(row) ?: return@withTextTable table
    val cells = r.cells.toMutableList()
    val cell = cells.getOrNull(col) ?: return@withTextTable table
    cells[col] = cell.copy(backgroundColor = color)
    rows[row] = OdfTableRow(cells)
    table.copy(rows = rows)
}

/** Merge a rectangular block of text-table cells (C27). */
fun OfficeViewModel.mergeTextTableCells(
    blockIndex: Int,
    startRow: Int,
    startCol: Int,
    endRow: Int,
    endCol: Int) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val colSpan = endCol - startCol + 1
    val rowSpan = endRow - startRow + 1
    if (colSpan < 1 || rowSpan < 1) return@withTextTable table
    for (r in startRow..endRow) {
        val rr = rows.getOrNull(r) ?: continue
        val cells = rr.cells.toMutableList()
        for (c in startCol..endCol) {
            if (c !in cells.indices) continue
            cells[c] = if (r == startRow && c == startCol) cells[c].copy(colSpan = colSpan, rowSpan = rowSpan)
            else cells[c].copy(isCovered = true)
        }
        rows[r] = OdfTableRow(cells)
    }
    table.copy(rows = rows)
}

fun OfficeViewModel.unmergeTextTableCells(blockIndex: Int, row: Int, col: Int) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val cell = rows.getOrNull(row)?.cells?.getOrNull(col) ?: return@withTextTable table
    if (cell.colSpan <= 1 && cell.rowSpan <= 1) return@withTextTable table
    for (r in row until minOf(row + cell.rowSpan, rows.size)) {
        val cells = rows[r].cells.toMutableList()
        for (c in col until minOf(col + cell.colSpan, cells.size)) {
            cells[c] = if (r == row && c == col) cells[c].copy(
                colSpan = 1,
                rowSpan = 1) else cells[c].copy(isCovered = false)
        }
        rows[r] = OdfTableRow(cells)
    }
    table.copy(rows = rows)
}
