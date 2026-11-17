package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.EditorBaseButtons
import com.vayunmathur.library.ui.EditorFormat
import com.vayunmathur.library.ui.EditorFormatter
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconFormatAlignCenter
import com.vayunmathur.library.ui.IconFormatAlignLeft
import com.vayunmathur.library.ui.IconFormatAlignRight
import com.vayunmathur.library.ui.IconFormatColorText
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.office.R
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.duplicateSlideElement
import com.vayunmathur.office.platform.fillDownToEnd
import com.vayunmathur.office.platform.setCellAlignment
import com.vayunmathur.office.platform.setCellBold
import com.vayunmathur.office.platform.setCellItalic
import com.vayunmathur.office.platform.setCellNumberFormat
import com.vayunmathur.office.platform.setSlideElementAlignment
import com.vayunmathur.office.platform.setSlideElementRotation
import com.vayunmathur.office.platform.toggleSlideElementUnderline
import com.vayunmathur.office.platform.unmergeCells

import com.vayunmathur.office.platform.reorderSlideElement
import com.vayunmathur.office.platform.toggleSlideElementBold
import com.vayunmathur.office.platform.toggleSlideElementItalic
// --- Spreadsheet cell branch ---

@Composable
internal fun CellFormatControls(target: FormatTarget.Cell?, viewModel: OfficeViewModel, actions: BottomBarActions) {
    val enabled = target != null
    val s = target?.sheet ?: -1
    val r = target?.row ?: -1
    val c = target?.col ?: -1
    var alignMenu by remember { mutableStateOf(false) }
    EditorBaseButtons(CellFormatter(viewModel, s, r, c, enabled))
    FmtIcon(false, enabled, { IconFormatColorText() }) { actions.onCellTextColor() }
    TextButton(onClick = { actions.onCellBgColor() }, enabled = enabled) { Text(stringResource(R.string.fill)) }
    Box {
        FmtIcon(false, enabled, { IconFormatAlignLeft() }) { alignMenu = true }
        DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.left)) },
                leadingIcon = { IconFormatAlignLeft() },
                onClick = { alignMenu = false; if (enabled) viewModel.setCellAlignment(s, r, c, TextAlign.Start) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.center)) },
                leadingIcon = { IconFormatAlignCenter() },
                onClick = { alignMenu = false; if (enabled) viewModel.setCellAlignment(s, r, c, TextAlign.Center) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.right)) },
                leadingIcon = { IconFormatAlignRight() },
                onClick = { alignMenu = false; if (enabled) viewModel.setCellAlignment(s, r, c, TextAlign.End) })
        }
    }
    TextButton(
        onClick = { if (enabled) viewModel.unmergeCells(s, r, c) },
        enabled = enabled) { Text(stringResource(R.string.unmerge)) }
    Box {
        var moreMenu by remember { mutableStateOf(false) }
        var numMenu by remember { mutableStateOf(false) }
        FmtIcon(false, enabled, { IconMoreVert() }) { moreMenu = true }
        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.fill_down)) },
                enabled = enabled,
                onClick = { moreMenu = false; if (enabled) viewModel.fillDownToEnd(s, r, c) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.border_color)) },
                enabled = enabled,
                onClick = { moreMenu = false; actions.onCellBorder() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.comment_1)) },
                enabled = enabled,
                onClick = { moreMenu = false; actions.onCellComment() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.row_column_size)) },
                enabled = enabled,
                onClick = { moreMenu = false; actions.onCellResize() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.number_format)) },
                trailingIcon = { IconArrowDropDown() },
                enabled = enabled,
                onClick = { numMenu = true })
        }
        DropdownMenu(expanded = numMenu, onDismissRequest = { numMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.general)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, null) })
            DropdownMenuItem(text = { Text(stringResource(R.string.number_2_dp)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 2, grouping = true)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.integer)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 0)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.percent)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 0, percent = true)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.currency)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 2, currencySymbol = "$", grouping = true)) })
            DropdownMenuItem(text = { Text(stringResource(UiR.string.date)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(isDate = true)) })
            DropdownMenuItem(text = { Text(stringResource(UiR.string.time)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(isTime = true)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.scientific)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 2, isScientific = true)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.fraction)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(isFraction = true, fractionDenominatorDigits = 2)) })
        }
    }
}

/** Inline character formatting for a spreadsheet cell. */
internal class CellFormatter(
    private val viewModel: OfficeViewModel,
    private val sheet: Int,
    private val row: Int,
    private val col: Int,
    override val enabled: Boolean,
) : EditorFormatter {
    override val supported = setOf(EditorFormat.BOLD, EditorFormat.ITALIC)
    override fun toggle(format: EditorFormat) {
        if (!enabled) return
        when (format) {
            EditorFormat.BOLD -> viewModel.setCellBold(sheet, row, col)
            EditorFormat.ITALIC -> viewModel.setCellItalic(sheet, row, col)
            else -> {}
        }
    }
}

/** Inline character formatting for a presentation slide element. */
internal class SlideFormatter(
    private val viewModel: OfficeViewModel,
    private val slide: Int,
    private val element: Int,
    override val enabled: Boolean,
) : EditorFormatter {
    override val supported = setOf(EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.UNDERLINE)
    override fun toggle(format: EditorFormat) {
        if (!enabled) return
        when (format) {
            EditorFormat.BOLD -> viewModel.toggleSlideElementBold(slide, element)
            EditorFormat.ITALIC -> viewModel.toggleSlideElementItalic(slide, element)
            EditorFormat.UNDERLINE -> viewModel.toggleSlideElementUnderline(slide, element)
            else -> {}
        }
    }
}

@Composable
internal fun ElementFormatControls(
    target: FormatTarget.Element?,
    viewModel: OfficeViewModel,
    actions: BottomBarActions) {
    val enabled = target != null
    val s = target?.slide ?: -1
    val e = target?.element ?: -1
    var alignMenu by remember { mutableStateOf(false) }
    EditorBaseButtons(SlideFormatter(viewModel, s, e, enabled))
    FmtIcon(false, enabled, { IconFormatColorText() }) { actions.onSlideTextColor() }
    TextButton(onClick = { actions.onSlideFill() }, enabled = enabled) { Text(stringResource(R.string.fill)) }
    TextButton(onClick = { actions.onSlideStroke() }, enabled = enabled) { Text(stringResource(R.string.border)) }
    Box {
        FmtIcon(false, enabled, { IconFormatAlignLeft() }) { alignMenu = true }
        DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.left)) },
                leadingIcon = { IconFormatAlignLeft() },
                onClick = { alignMenu = false; if (enabled) viewModel.setSlideElementAlignment(s, e, TextAlign.Start) })
            DropdownMenuItem(text = { Text(stringResource(R.string.center)) }, leadingIcon = { IconFormatAlignCenter() }, onClick = { alignMenu = false; if (enabled) viewModel.setSlideElementAlignment(s, e, TextAlign.Center) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.right)) },
                leadingIcon = { IconFormatAlignRight() },
                onClick = { alignMenu = false; if (enabled) viewModel.setSlideElementAlignment(s, e, TextAlign.End) })
        }
    }
    FmtIcon(false, enabled, { IconDelete() }) { if (enabled) actions.onDeleteElement() }
    Box {
        var moreMenu by remember { mutableStateOf(false) }
        FmtIcon(false, enabled, { IconMoreVert() }) { moreMenu = true }
        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.duplicate)) },
                enabled = enabled,
                onClick = { moreMenu = false; if (enabled) viewModel.duplicateSlideElement(s, e) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bring_to_front)) },
                enabled = enabled,
                onClick = { moreMenu = false; if (enabled) viewModel.reorderSlideElement(s, e, true) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.send_to_back)) },
                enabled = enabled,
                onClick = { moreMenu = false; if (enabled) viewModel.reorderSlideElement(s, e, false) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rotate_90)) },
                enabled = enabled,
                onClick = { moreMenu = false; if (enabled) viewModel.setSlideElementRotation(s, e, 90f) })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.speaker_notes_1)) },
                onClick = { moreMenu = false; actions.onSlideNotes() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.slide_background)) },
                onClick = { moreMenu = false; actions.onSlideBackground() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.slide_transition)) },
                onClick = { moreMenu = false; actions.onSlideTransition() })
        }
    }
}
