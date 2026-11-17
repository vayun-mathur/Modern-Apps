package com.vayunmathur.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.code.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.code.domain.DiffRow
import com.vayunmathur.code.domain.DiffRowType
import com.vayunmathur.code.domain.Resolution
import com.vayunmathur.code.domain.parseConflicts
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

private val MONO = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

/** A side-by-side diff viewer: old on the left, new on the right, with add/remove tinting. */
@Composable
fun SideBySideDiffDialog(rows: List<DiffRow>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diff)) },
        text = {
            if (rows.isEmpty()) {
                Text(stringResource(R.string.no_changes), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    items(rows) { row -> DiffRowView(row) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.close)) }
        },
    )
}

@Composable
private fun DiffRowView(row: DiffRow) {
    if (row.type == DiffRowType.HUNK) {
        Text(
            text = row.text,
            style = MONO,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
        return
    }
    val removeBg = MaterialTheme.colorScheme.errorContainer
    val addBg = MaterialTheme.colorScheme.tertiaryContainer
    val leftText = if (row.type == DiffRowType.ADD) "" else row.text
    val rightText = if (row.type == DiffRowType.REMOVE) "" else row.text
    val leftBg = if (row.type == DiffRowType.REMOVE) removeBg else Color.Transparent
    val rightBg = if (row.type == DiffRowType.ADD) addBg else Color.Transparent
    Row(Modifier.fillMaxWidth()) {
        DiffCell(leftText, leftBg)
        Text(" ", style = MONO)
        DiffCell(rightText, rightBg)
    }
}

@Composable
private fun DiffCell(text: String, background: Color) {
    Text(
        text = text,
        style = MONO,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .width(260.dp)
            .background(background)
            .padding(horizontal = 4.dp),
    )
}

