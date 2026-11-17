package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.office.ui.ColorPickerDialog
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.cellCommentText
import com.vayunmathur.office.platform.setCellBgColor
import com.vayunmathur.office.platform.setCellBorder
import com.vayunmathur.office.platform.setCellColor
import com.vayunmathur.office.platform.setCellComment
import com.vayunmathur.office.platform.setColumnWidth
import com.vayunmathur.office.platform.setRowHeight

import com.vayunmathur.office.platform.save
import com.vayunmathur.office.R
private val CommentFieldHeight = 120.dp
private val DialogSpacing = 8.dp

/** Cell + slide-element overlays (split from OfficeDocumentOverlays.kt). */

@Composable
internal fun CellOverlays(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    activeCell: MutableState<Triple<Int, Int, Int>?>,
    activeSlide: MutableState<Int>,
    activeSlideEl: MutableState<Int>,
) {
    CellColorOverlays(state, viewModel, activeCell)
    SlideElementOverlays(state, viewModel, activeSlide, activeSlideEl)
    CellCommentOverlay(state, viewModel, activeCell)
    CellResizeOverlay(state, viewModel, activeCell)
}

/** Cell text/bg/border color pickers. */
@Composable
private fun CellColorOverlays(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    activeCell: MutableState<Triple<Int, Int, Int>?>,
) {
    val (s, r, c) = activeCell.value?.takeIf { (it.second) >= 0 } ?: return
    if (state.showCellTextColor) {
        ColorPickerDialog(
            "Text Color",
            onColorSelected = { viewModel.setCellColor(s, r, c, it) },
            onDismiss = { state.showCellTextColor = false })
    }
    if (state.showCellBgColor) {
        ColorPickerDialog(
            "Background Color",
            onColorSelected = { viewModel.setCellBgColor(s, r, c, it) },
            onDismiss = { state.showCellBgColor = false })
    }
    if (state.showCellBorderColor) {
        ColorPickerDialog(
            "Border Color",
            onColorSelected = { viewModel.setCellBorder(s, r, c, it) },
            onDismiss = { state.showCellBorderColor = false })
    }
}

/** Cell comment editor. */
@Composable
private fun CellCommentOverlay(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    activeCell: MutableState<Triple<Int, Int, Int>?>,
) {
    val (s, r, c) = activeCell.value?.takeIf { state.showCellComment && it.second >= 0 } ?: return
    var text by remember(state.showCellComment) { mutableStateOf(viewModel.cellCommentText(s, r, c)) }
    AlertDialog(
        onDismissRequest = { state.showCellComment = false },
        title = { Text(stringResource(R.string.cell_comment)) },
        text = { TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.comment)) },
            modifier = Modifier.height(CommentFieldHeight)) },
        confirmButton = { TextButton(onClick = {
            viewModel.setCellComment(s, r, c, "", text)
            state.showCellComment = false
        }) { Text(stringResource(UiR.string.save)) } },
        dismissButton =
            { TextButton(onClick = { state.showCellComment = false }) { Text(stringResource(UiR.string.cancel)) } }
    )
}

/** Cell row/column resize editor. */
@Composable
private fun CellResizeOverlay(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    activeCell: MutableState<Triple<Int, Int, Int>?>,
) {
    val (s, r, c) = activeCell.value?.takeIf { state.showCellResize && it.second >= 0 } ?: return
    var w by remember(state.showCellResize) { mutableStateOf("") }
    var h by remember(state.showCellResize) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { state.showCellResize = false },
        title = { Text(stringResource(R.string.row_column_size_1)) },
        text = {
            Column {
                TextField(
                    value = w,
                    onValueChange = { w = it },
                    label = { Text(stringResource(R.string.column_width_px)) })
                Spacer(Modifier.height(DialogSpacing))
                TextField(
                    value = h,
                    onValueChange = { h = it },
                    label = { Text(stringResource(R.string.row_height_px)) })
            }
        },
        confirmButton = { TextButton(onClick = {
            w.toFloatOrNull()?.let { viewModel.setColumnWidth(s, c, it) }
            h.toFloatOrNull()?.let { viewModel.setRowHeight(s, r, it) }
            state.showCellResize = false
        }) { Text(stringResource(UiR.string.apply)) } },
        dismissButton =
            { TextButton(onClick = { state.showCellResize = false }) { Text(stringResource(UiR.string.cancel)) } }
    )
}
