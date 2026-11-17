package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextFieldDefaults
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.R
import com.vayunmathur.office.data.odf.ExcelFormula

/** Cell edit bar above the grid: address label, formula field, done. State lives in [SpreadsheetView]. */
@Composable
internal fun SpreadsheetEditBar(
    doc: OdfDocument.Spreadsheet,
    selectedSheet: Int,
    editingCell: Triple<Int, Int, Int>,
    editText: TextFieldValue,
    onEditTextChange: (TextFieldValue) -> Unit,
    onCommitCell: (Int, Int, Int, String) -> Unit,
    onAdvanceCell: (Triple<Int, Int, Int>?, TextFieldValue?) -> Unit,
    onCellSelected: (Int, Int, Int) -> Unit,
) {
    val (_, ri, ci) = editingCell
    val focusRequester = remember { FocusRequester() }
    val rowCount = doc.sheets[selectedSheet].rows.size
    LaunchedEffect(editingCell) { try { focusRequester.requestFocus() } catch (_: Exception) {} }
    fun commitAndAdvance() {
        val (si, r, c) = editingCell
        onCommitCell(si, r, c, editText.text)
        if (r + 1 < rowCount) {
            onCellSelected(si, r + 1, c)
            val nextText =
                doc.sheets[si].rows.getOrNull(r + 1)?.cells?.getOrNull(c)?.let {
                    it.formula?.let { f -> try { ExcelFormula.fromOdf(f) } catch (_: Exception) { f } } ?: it.text
                } ?: ""
            onAdvanceCell(Triple(si, r + 1, c), TextFieldValue(nextText, TextRange(0, nextText.length)))
        } else { onAdvanceCell(null, null); onCellSelected(si, -1, -1) }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${columnLabel(ci)}${ri + 1}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 8.dp))
        TextField(value = editText, onValueChange = onEditTextChange, singleLine = true,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { commitAndAdvance() }, onDone = { commitAndAdvance() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant))
        TextButton(onClick = {
            val (si, r, c) = editingCell
            onCommitCell(si, r, c, editText.text); onAdvanceCell(null, null); onCellSelected(si, -1, -1)
        }) { Text(stringResource(UiR.string.done)) }
    }
}

/** Row/column/sheet edit actions below the tab bar. State lives in [SpreadsheetView]. */
@Composable
internal fun SpreadsheetEditToolbar(
    doc: OdfDocument.Spreadsheet,
    selectedSheet: Int,
    editingCell: Triple<Int, Int, Int>?,
    onAddRow: (Int, Int) -> Unit,
    onAddColumn: (Int) -> Unit,
    onDeleteRow: (Int, Int) -> Unit,
    onDeleteColumn: (Int, Int) -> Unit,
    onEditingCleared: () -> Unit,
    onShowSort: () -> Unit,
    onSetFreeze: (Int, Int, Int) -> Unit,
    onDeleteSheetClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { val ri = editingCell?.second ?: (doc.sheets[selectedSheet].rows.size - 1); onAddRow(selectedSheet, ri) }) { Text(stringResource(R.string.row_1)) }
        TextButton(onClick = { onAddColumn(selectedSheet) }) { Text(stringResource(R.string.col_1)) }
        if (editingCell != null) {
            TextButton(onClick = { onDeleteRow(
                selectedSheet,
                editingCell.second); onEditingCleared() }) { Text(stringResource(R.string.row)) }
            TextButton(onClick = { onDeleteColumn(
                selectedSheet,
                editingCell.third); onEditingCleared() }) { Text(stringResource(R.string.col)) }
        }
        TextButton(onClick = onShowSort) { Text(stringResource(R.string.sort)) }
        run {
            val sheet0 = doc.sheets[selectedSheet]
            val frozen = sheet0.freezeRows > 0 || sheet0.freezeCols > 0
            if (frozen) {
                TextButton(onClick = { onSetFreeze(
                    selectedSheet,
                    0,
                    0) }) { Text(stringResource(R.string.unfreeze)) }
            } else {
                TextButton(onClick = {
                    // Freeze rows above and columns left of the active/editing cell (default: header row).
                    val r = editingCell?.second ?: 1
                    val c = editingCell?.third ?: 0
                    onSetFreeze(selectedSheet, r, c)
                }) { Text(stringResource(R.string.freeze)) }
            }
        }
        Spacer(Modifier.weight(1f))
        if (doc.sheets.size > 1) TextButton(onClick = onDeleteSheetClick) { Text(stringResource(R.string.sheet_1), color = MaterialTheme.colorScheme.error) }
    }
}

/** Rename-sheet + sort dialogs. State lives in [SpreadsheetView]. */
@Composable
internal fun SpreadsheetDialogs(
    doc: OdfDocument.Spreadsheet,
    selectedSheet: Int,
    showRenameSheet: Boolean,
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    onRenameConfirm: (String) -> Unit,
    onRenameDismiss: () -> Unit,
    showSortDialog: Boolean,
    onSort: (Int, Boolean) -> Unit,
    onSortDismiss: () -> Unit,
) {
    if (showRenameSheet) {
        AlertDialog(onDismissRequest = onRenameDismiss, title = { Text(stringResource(R.string.rename_sheet)) },
            text = { TextField(value = renameText, onValueChange = onRenameTextChange, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRenameConfirm(renameText) }) { Text(stringResource(UiR.string.ok)) } },
            dismissButton =
                { TextButton(onClick = onRenameDismiss) { Text(stringResource(UiR.string.cancel)) } })
    }
    if (showSortDialog) {
        val maxC = doc.sheets[selectedSheet].rows.maxOfOrNull { it.cells.size } ?: 1
        SortDialog(
            maxC,
            onSort = onSort,
            onDismiss = onSortDismiss)
    }
}
