package com.vayunmathur.office.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.PrimaryScrollableTabRow
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.odf.OdfBorders
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.R
import com.vayunmathur.office.data.odf.ExcelFormula
import com.vayunmathur.office.util.OfficeNative

@Composable
fun SpreadsheetView(
    doc: OdfDocument.Spreadsheet, searchQuery: String = "", fontSizeMultiplier: Float = 1f,
    isEditMode: Boolean = false,
    onCellTextChange: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onAddRow: (Int, Int) -> Unit = { _, _ -> }, onAddColumn: (Int) -> Unit = {},
    onDeleteRow: (Int, Int) -> Unit = { _, _ -> }, onDeleteColumn: (Int, Int) -> Unit = { _, _ -> },
    onRenameSheet: (Int, String) -> Unit = { _, _ -> }, onAddSheet: () -> Unit = {}, onDeleteSheet: (Int) -> Unit = {},
    onSort: (Int, Int, Boolean) -> Unit = { _, _, _ -> },
    onCellSelected: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onFloatingBoundsChange: (Int, Int, Float, Float, Float, Float) -> Unit = { _, _, _, _, _, _ -> },
    onFloatingTextChange: (Int, Int, String) -> Unit = { _, _, _ -> },
    onFloatingDelete: (Int, Int) -> Unit = { _, _ -> },
    onFloatingCrop: (Int, Int) -> Unit = { _, _ -> },
    onSetFreeze: (Int, Int, Int) -> Unit = { _, _, _ -> },
    /**
     * Where evaluated cell text comes from. Defaults to the native formula engine, which is
     * what the app wants; a `@Preview` passes a literal source instead, because Layoutlib
     * cannot load `office_engine` and merely touching [OfficeNative] would try to.
     */
    values: SpreadsheetValues = rememberNativeSpreadsheetValues(doc)
) {
    if (doc.sheets.isEmpty()) { Text(
        stringResource(R.string.empty_spreadsheet),
        modifier = Modifier.padding(16.dp)); return }

    var selectedSheet by remember { mutableIntStateOf(0) }
    var selectedFloating by remember { mutableIntStateOf(-1) }
    var editingCell by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var dropdownCell by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    val validationsByName = remember(doc) { doc.validations.associateBy { it.name } }
    var editText by remember { mutableStateOf(TextFieldValue("")) }
    var showRenameSheet by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showSortDialog by remember { mutableStateOf(false) }

    // A3: keep the selected sheet/floating indices valid after undo/redo shrinks the lists.
    selectedSheet = selectedSheet.coerceIn(0, doc.sheets.size - 1)
    if (selectedFloating >= doc.sheets[selectedSheet].floating.size) selectedFloating = -1

    Column(modifier = Modifier.fillMaxSize()) {
        if (doc.sheets.size > 1 || isEditMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryScrollableTabRow(selectedTabIndex = selectedSheet, modifier = Modifier.weight(1f)) {
                    doc.sheets.forEachIndexed { index, sheet ->
                        Tab(selected = selectedSheet == index, onClick = { selectedSheet = index; editingCell = null; selectedFloating = -1; onCellSelected(index, -1, -1) },
                            text = { if (isEditMode) Text(
                                sheet.name,
                                Modifier.clickable { renameText = sheet.name; showRenameSheet = true })
                            else Text(sheet.name) })
                    }
                }
                if (isEditMode) TextButton(onClick = { onAddSheet() }) { Text("+") }
            }
        } else {
            Text(
                doc.sheets[0].name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp, 8.dp))
        }

        if (isEditMode && editingCell != null) {
            SpreadsheetEditBar(
                doc = doc,
                selectedSheet = selectedSheet,
                editingCell = editingCell!!,
                editText = editText,
                onEditTextChange = { editText = it },
                onCommitCell = onCellTextChange,
                onAdvanceCell = { next, nextText -> editingCell = next; if (nextText != null) editText = nextText },
                onCellSelected = onCellSelected)
        }

        if (isEditMode) {
            SpreadsheetEditToolbar(
                doc = doc,
                selectedSheet = selectedSheet,
                editingCell = editingCell,
                onAddRow = onAddRow,
                onAddColumn = onAddColumn,
                onDeleteRow = onDeleteRow,
                onDeleteColumn = onDeleteColumn,
                onEditingCleared = { editingCell = null },
                onShowSort = { showSortDialog = true },
                onSetFreeze = onSetFreeze,
                onDeleteSheetClick = { onDeleteSheet(selectedSheet); if (selectedSheet >= doc.sheets.size - 1) selectedSheet = maxOf(0, doc.sheets.size - 2) })
        }

        val sheet = doc.sheets[selectedSheet]
        val maxCols = sheet.rows.maxOfOrNull { it.cells.count { c -> !c.isCovered } } ?: 0
        fun colWidthDp(start: Int, span: Int): androidx.compose.ui.unit.Dp {
            var w = 0f
            for (k in start until start + span) {
                val px = sheet.columnWidths.getOrNull(k)
                w += if (px != null && px > 0f) (px * (160f / 96f)).coerceIn(40f, 400f) else 84f
            }
            return w.dp
        }

        // A1: anchor floating objects inside the scrolled grid content (rides both scroll axes).
        // Map model px@96 -> grid content dp by sizing the overlay to the content and passing the
        // content's dp dimensions as the reference space.
        val hScroll = rememberScrollState()
        var contentWidthDp = 40f
        for (col in 0 until maxCols) contentWidthDp += colWidthDp(col, 1).value
        val contentHeightDp = 26f + sheet.rows.size * 33f

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(Modifier.horizontalScroll(hScroll).padding(horizontal = 4.dp)) {
                    Box {
                        Column {
                            Row {
                                Box(Modifier.defaultMinSize(minWidth = 40.dp).background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(4.dp), contentAlignment = Alignment.Center) { Text("") }
                                for (col in 0 until maxCols) Box(Modifier.width(colWidthDp(col, 1)).background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(4.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        columnLabel(col),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                            for ((rowIdx, row) in sheet.rows.withIndex()) {
                                Row(Modifier.height(IntrinsicSize.Min)) {
                                    Box(Modifier.defaultMinSize(minWidth = 40.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(4.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            "${rowIdx + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold)
                                    }
                                    var colspanSkip = 0
                                    for ((cellIdx, cell) in row.cells.withIndex()) {
                                        if (cell.isCovered) {
                                            // Covered by a horizontal merge to its left: consume silently.
                                            if (colspanSkip > 0) { colspanSkip--; continue }
                                            // Covered by a vertical (row) merge from above: render an empty
                                            // aligned placeholder so columns don't shift. (Tier 0 bugfix)
                                            Box(Modifier.width(colWidthDp(cellIdx, 1)).fillMaxHeight()
                                                .border(0.5.dp, MaterialTheme.colorScheme.outline)) {}
                                            continue
                                        }
                                        colspanSkip = if (cell.spannedColumns > 1) cell.spannedColumns - 1 else 0
                                        val isEditing = editingCell?.let { it.first == selectedSheet && it.second == rowIdx && it.third == cellIdx } == true
                                        val isMatch = searchQuery.isNotEmpty() && cell.text.contains(
                                            searchQuery,
                                            ignoreCase = true)
                                        val displayText = values.display(selectedSheet, rowIdx, cellIdx)
                                        val cf = if (cell.condFormats.isEmpty()) null else evalCondFormat(
                                            cell.condFormats,
                                            cell.numberValue ?: displayText.toDoubleOrNull(),
                                            displayText)
                                        val effBg = cf?.backgroundColor ?: cell.backgroundColor
                                        Box(
                                            Modifier.width(colWidthDp(cellIdx, cell.spannedColumns))
                                                .fillMaxHeight()
                                                .border(if (isEditing) 2.dp else 0.5.dp, if (isEditing) MaterialTheme.colorScheme.primary else (cell.borderColor?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.outline))
                                                .then(if (!isEditing && cell.borders?.isEmpty() == false) Modifier.drawBehind {
                                                    val sw = 1.5.dp.toPx()
                                                    OdfBorders.renderColor(cell.borders!!.top)?.let { drawLine(
                                                        Color(it.toInt()),
                                                        Offset(0f, 0f),
                                                        Offset(size.width, 0f),
                                                        sw) }
                                                    OdfBorders.renderColor(cell.borders!!.bottom)?.let { drawLine(
                                                        Color(it.toInt()),
                                                        Offset(0f, size.height),
                                                        Offset(size.width, size.height),
                                                        sw) }
                                                    OdfBorders.renderColor(cell.borders!!.left)?.let { drawLine(
                                                        Color(it.toInt()),
                                                        Offset(0f, 0f),
                                                        Offset(0f, size.height),
                                                        sw) }
                                                    OdfBorders.renderColor(cell.borders!!.right)?.let { drawLine(
                                                        Color(it.toInt()),
                                                        Offset(size.width, 0f),
                                                        Offset(size.width, size.height),
                                                        sw) }
                                                } else Modifier)
                                                .then(if (isMatch) Modifier.background(Color(0xFFFFEB3B).copy(alpha = 0.3f)) else effBg?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
                                                .then(if (isEditMode) Modifier.clickable { editingCell = Triple(selectedSheet, rowIdx, cellIdx); selectedFloating = -1; onCellSelected(selectedSheet, rowIdx, cellIdx); val t = cell.formula?.let { try { ExcelFormula.fromOdf(it) } catch (_: Exception) { it } } ?: cell.text; editText = TextFieldValue(t, TextRange(0, t.length)) } else Modifier)
                                                .then(if (cell.annotation != null) Modifier.drawBehind {
                                                    // Red corner triangle marks a cell comment. (Round 3)
                                                    val s = 7.dp.toPx()
                                                    drawPath(androidx.compose.ui.graphics.Path().apply {
                                                        moveTo(size.width - s, 0f); lineTo(size.width, 0f); lineTo(
                                                            size.width,
                                                            s); close()
                                                    }, Color(0xFFD32F2F))
                                                } else Modifier)
                                                .padding(8.dp, 4.dp)
                                        ) {
                                            val cellAlign = cell.alignment ?: if (values.isNumeric(
                                                selectedSheet,
                                                rowIdx,
                                                cellIdx)) TextAlign.End else null
                                            Text(displayText,
                                                style = MaterialTheme.typography.bodyMedium.let { if (fontSizeMultiplier != 1f && it.fontSize != TextUnit.Unspecified) it.copy(fontSize = it.fontSize * fontSizeMultiplier) else it },
                                                fontWeight = if (cell.bold) FontWeight.Bold else null,
                                                fontStyle = if (cell.italic) FontStyle.Italic else null,
                                                color = (cf?.textColor ?: cell.textColor)?.let { Color(it.toInt()) } ?: Color.Unspecified,
                                                textAlign = cellAlign, maxLines = if (cell.wrap) Int.MAX_VALUE else 3)
                                            // Data-validation list dropdown (Round 3).
                                            val listVals =
                                                cell.validationName?.let { validationsByName[it]?.listValues() }
                                            if (listVals != null && isEditMode) {
                                                val here = Triple(selectedSheet, rowIdx, cellIdx)
                                                Box(Modifier.align(Alignment.CenterEnd)) {
                                                    Text(
                                                        "▾",
                                                        Modifier.clickable { dropdownCell = here },
                                                        fontWeight = FontWeight.Bold)
                                                    DropdownMenu(expanded = dropdownCell == here, onDismissRequest = { if (dropdownCell == here) dropdownCell = null }) {
                                                        for (opt in listVals) DropdownMenuItem(text = { Text(opt) }, onClick = { onCellTextChange(selectedSheet, rowIdx, cellIdx, opt); dropdownCell = null })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (isEditMode || sheet.floating.isNotEmpty()) {
                            FloatingElementLayer(
                                elements = sheet.floating, refW = contentWidthDp, refH = contentHeightDp,
                                modifier = Modifier.matchParentSize(),
                                editMode = isEditMode, selectedIndex = selectedFloating,
                                keyPrefix = "sheet$selectedSheet", interactiveBackground = false,
                                onSelect = { selectedFloating = it },
                                onElementTextChange = { ei, t -> onFloatingTextChange(selectedSheet, ei, t) },
                                onBoundsChange = { ei, x, y, w, h -> onFloatingBoundsChange(
                                    selectedSheet,
                                    ei,
                                    x,
                                    y,
                                    w,
                                    h) },
                                onDelete = { ei -> onFloatingDelete(selectedSheet, ei); selectedFloating = -1 },
                                onCropImage = { ei -> onFloatingCrop(selectedSheet, ei) }
                            )
                        }
                    }
                }
            }
        }
    }

    SpreadsheetDialogs(
        doc = doc,
        selectedSheet = selectedSheet,
        showRenameSheet = showRenameSheet,
        renameText = renameText,
        onRenameTextChange = { renameText = it },
        onRenameConfirm = { onRenameSheet(selectedSheet, it); showRenameSheet = false },
        onRenameDismiss = { showRenameSheet = false },
        showSortDialog = showSortDialog,
        onSort = { col, asc -> onSort(selectedSheet, col, asc) },
        onSortDismiss = { showSortDialog = false })
}
