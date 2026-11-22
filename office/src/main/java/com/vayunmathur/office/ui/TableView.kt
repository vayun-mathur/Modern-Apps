package com.vayunmathur.office.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.office.R

@Composable
fun TableView(
    table: com.vayunmathur.library.ui.odf.OdfTable,
    blockIndex: Int,
    fontSizeMultiplier: Float = 1f,
    onCellTextChange: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onCellFocus: (Int, Int, Int) -> Unit = { _, _, _ -> },
    firstCellFocusRequester: FocusRequester? = null
) {
    // First editable cell in row-major (visual) order, for Escape-to-body.
    val firstCell: Pair<Int, Int>? = table.rows.indices.firstNotNullOfOrNull { r ->
        table.rows[r].cells.indexOfFirst { !it.isCovered }.takeIf { it >= 0 }?.let { c -> r to c }
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    fun colWidthDp(start: Int, span: Int): androidx.compose.ui.unit.Dp {
        var w = 0f
        for (k in start until start + span) {
            val px = table.columns.getOrNull(k)?.width
            w += if (px != null && px > 0f) px * (160f / 96f) else 110f
        }
        return w.dp
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).horizontalScroll(rememberScrollState())) {
        for ((r, row) in table.rows.withIndex()) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                var colspanSkip = 0
                for ((c, cell) in row.cells.withIndex()) {
                    if (cell.isCovered) {
                        // Covered by a horizontal merge to its left: the anchor already claimed the width.
                        if (colspanSkip > 0) { colspanSkip--; continue }
                        // Covered by a vertical (row) merge from above: render an aligned placeholder so
                        // columns below the merge don't collapse and shift left.
                        Box(Modifier.width(colWidthDp(c, 1)).fillMaxHeight()
                            .border(0.7.dp, MaterialTheme.colorScheme.outline)) {}
                        continue
                    }
                    colspanSkip = if (cell.colSpan > 1) cell.colSpan - 1 else 0
                    Box(
                        Modifier.width(colWidthDp(c, cell.colSpan))
                            .fillMaxHeight()
                            .border(0.7.dp, MaterialTheme.colorScheme.outline)
                            .then(cell.backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
                            .padding(8.dp)
                    ) {
                        EditableCell(
                            cell,
                            onSurface,
                            fontSizeMultiplier,
                            onFocus = { onCellFocus(blockIndex, r, c) },
                            focusRequester = if (firstCell == (r to c)) firstCellFocusRequester else null)
                        { txt -> onCellTextChange(blockIndex, r, c, txt) }
                    }
                }
            }
        }
        if (table.rows.isEmpty()) Text(
            stringResource(R.string.empty_table),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun EditableCell(
    cell: OdfTableCell,
    onSurface: Color,
    mult: Float,
    onFocus: () -> Unit,
    focusRequester: FocusRequester? = null,
    onChange: (String) -> Unit) {
    var cellFocused by remember { mutableStateOf(false) }
    val plain = cell.paragraphs.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    var tfv by remember { mutableStateOf(TextFieldValue(plain)) }
    if (tfv.text != plain) tfv = TextFieldValue(plain, TextRange(tfv.selection.end.coerceIn(0, plain.length)))
    // Reflect uniform cell character formatting (C26).
    val firstSpan = cell.paragraphs.firstOrNull()?.spans?.firstOrNull()
    val cellAlign = cell.paragraphs.firstOrNull()?.alignment
    val style = MaterialTheme.typography.bodyMedium.copy(
        color = firstSpan?.color?.let { Color(it.toInt()) } ?: onSurface,
        fontSize = (14f * mult).sp,
        fontWeight = if (firstSpan?.bold == true) FontWeight.Bold else null,
        fontStyle = if (firstSpan?.italic == true) FontStyle.Italic else null,
        textAlign = cellAlign ?: TextAlign.Unspecified
    )
    BasicTextField(
        value = tfv,
        onValueChange = { nv -> val changed = nv.text != tfv.text; tfv = nv; if (changed) onChange(nv.text) },
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { cellFocused = it.isFocused; if (it.isFocused) onFocus() }
            .officeFocusRing(cellFocused)
    )
}
