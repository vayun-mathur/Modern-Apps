package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.EditorBaseButtons
import com.vayunmathur.library.ui.EditorFormat
import com.vayunmathur.library.ui.EditorFormatter
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheckBox
import com.vayunmathur.library.ui.IconFormatAlignCenter
import com.vayunmathur.library.ui.IconFormatAlignJustify
import com.vayunmathur.library.ui.IconFormatAlignLeft
import com.vayunmathur.library.ui.IconFormatAlignRight
import com.vayunmathur.library.ui.IconFormatColorText
import com.vayunmathur.library.ui.IconFormatIndentDecrease
import com.vayunmathur.library.ui.IconFormatIndentIncrease
import com.vayunmathur.library.ui.IconFormatListBulleted
import com.vayunmathur.library.ui.IconFormatListNumbered
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.LinkContext
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.R
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.applyRunSpanStyle
import com.vayunmathur.office.platform.changeListLevel
import com.vayunmathur.office.platform.clearRunFormatting
import com.vayunmathur.office.platform.deleteParagraph
import com.vayunmathur.office.platform.duplicateParagraph
import com.vayunmathur.office.platform.indentParagraph
import com.vayunmathur.office.platform.linkAt
import com.vayunmathur.office.platform.mergeTextTableCells
import com.vayunmathur.office.platform.moveParagraphDown
import com.vayunmathur.office.platform.moveParagraphUp
import com.vayunmathur.office.platform.mutateRunParagraphs
import com.vayunmathur.office.platform.outdentParagraph
import com.vayunmathur.office.platform.removeLinkInRun
import com.vayunmathur.office.platform.setLink
import com.vayunmathur.office.platform.setTextTableCellSpanFormat
import com.vayunmathur.office.platform.unmergeTextTableCells
import androidx.compose.ui.res.stringResource

import com.vayunmathur.office.platform.restartNumbering
import com.vayunmathur.office.platform.toggleCheckbox
import com.vayunmathur.office.platform.toggleListItem
import com.vayunmathur.office.platform.toggleNumberedList
import com.vayunmathur.office.platform.runParagraphIndexAt
import com.vayunmathur.office.platform.runRangeHasFormat
import com.vayunmathur.office.platform.runSelectedText
import com.vayunmathur.office.platform.textTableAddColumn
import com.vayunmathur.office.platform.textTableAddRow
import com.vayunmathur.office.platform.textTableDeleteColumn
import com.vayunmathur.office.platform.textTableDeleteRow
// --- Text document branch (ported from QuickFormatBar) ---

@Composable
internal fun TextFormatControls(
    doc: OdfDocument.TextDocument,
    target: FormatTarget.TextRun?,
    viewModel: OfficeViewModel,
    activeTableBlock: Int,
    activeTableRow: Int,
    activeTableCol: Int,
    actions: BottomBarActions
) {
    val enabled = target != null
    val runStart = target?.runStart ?: -1
    val runEnd = target?.runEnd ?: -1
    val selStart = target?.selStart ?: 0
    val selEnd = target?.selEnd ?: 0
    val focusedPara = if (enabled) viewModel.runParagraphIndexAt(runStart, runEnd, selStart) else -1
    val para = (doc.content.getOrNull(focusedPara) as? OdfContentBlock.Paragraph)?.paragraph
    var styleMenu by remember { mutableStateOf(false) }
    var alignMenu by remember { mutableStateOf(false) }
    var tableMenu by remember { mutableStateOf(false) }

    val isBullet = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.BULLET
    val isNumbered = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.NUMBERED
    val isCheckbox = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.CHECKBOX

    val textFormatter = TextRunFormatter(viewModel, runStart, runEnd, selStart, selEnd, enabled)

    val styleLabel = when (para?.style) {
        ParagraphStyle.HEADING1 ->
            "H1"; ParagraphStyle.HEADING2 ->
                "H2"; ParagraphStyle.HEADING3 -> "H3"; ParagraphStyle.HEADING4 -> "H4"; else -> "Normal"
    }
    val alignIcon: @Composable () -> Unit = when (para?.alignment) {
        TextAlign.Center -> { { IconFormatAlignCenter() } }
        TextAlign.End, TextAlign.Right -> { { IconFormatAlignRight() } }
        TextAlign.Justify -> { { IconFormatAlignJustify() } }
        else -> { { IconFormatAlignLeft() } }
    }
    fun setStyle(s: ParagraphStyle) { viewModel.mutateRunParagraphs(
        runStart,
        runEnd,
        selStart,
        selEnd) { it.copy(style = s) } }
    fun setAlign(a: TextAlign) { viewModel.mutateRunParagraphs(
        runStart,
        runEnd,
        selStart,
        selEnd) { it.copy(alignment = a) } }

    Box {
        TextButton(onClick = { styleMenu = true }, enabled = enabled) {
            Text(styleLabel)
            IconArrowDropDown()
        }
        DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.normal)) },
                onClick = { styleMenu = false; setStyle(ParagraphStyle.BODY) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.heading_1)) },
                onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING1) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.heading_2)) },
                onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING2) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.heading_3)) },
                onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING3) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.heading_4)) },
                onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING4) })
        }
    }
    EditorBaseButtons(textFormatter)
    FmtIcon(false, enabled, { IconFormatColorText() }) { actions.onTextColor() }
    Box {
        FmtIcon(false, enabled, alignIcon) { alignMenu = true }
        DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.left)) },
                leadingIcon = { IconFormatAlignLeft() },
                onClick = { alignMenu = false; setAlign(TextAlign.Start) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.center)) },
                leadingIcon = { IconFormatAlignCenter() },
                onClick = { alignMenu = false; setAlign(TextAlign.Center) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.right)) },
                leadingIcon = { IconFormatAlignRight() },
                onClick = { alignMenu = false; setAlign(TextAlign.End) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.justify)) },
                leadingIcon = { IconFormatAlignJustify() },
                onClick = { alignMenu = false; setAlign(TextAlign.Justify) })
        }
    }
    FmtIcon(
        isBullet,
        enabled,
        { IconFormatListBulleted() }) { if (focusedPara >= 0) viewModel.toggleListItem(focusedPara) }
    FmtIcon(
        isNumbered,
        enabled,
        { IconFormatListNumbered() }) { if (focusedPara >= 0) viewModel.toggleNumberedList(focusedPara) }
    FmtIcon(isCheckbox, enabled, { IconCheckBox() }) { if (focusedPara >= 0) viewModel.toggleCheckbox(focusedPara) }
    FmtIcon(false, enabled, { IconFormatIndentIncrease() }) {
        if (focusedPara >= 0) {
            if (para?.style == ParagraphStyle.LIST_ITEM) viewModel.changeListLevel(focusedPara, 1)
            else viewModel.indentParagraph(focusedPara)
        }
    }
    FmtIcon(false, enabled, { IconFormatIndentDecrease() }) {
        if (focusedPara >= 0) {
            if (para?.style == ParagraphStyle.LIST_ITEM) viewModel.changeListLevel(focusedPara, -1)
            else viewModel.outdentParagraph(focusedPara)
        }
    }
    Box {
        val tableEnabled = activeTableBlock >= 0
        TextButton(onClick = { tableMenu = true }) {
            Text(stringResource(R.string.table))
            IconArrowDropDown()
        }
        DropdownMenu(expanded = tableMenu, onDismissRequest = { tableMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.insert_table)) },
                onClick = { tableMenu = false; actions.onInsertTable() })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.insert_row_below)) },
                enabled = tableEnabled,
                onClick = { tableMenu = false; viewModel.textTableAddRow(activeTableBlock, activeTableRow) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.insert_column_right)) },
                enabled = tableEnabled,
                onClick = { tableMenu = false; viewModel.textTableAddColumn(activeTableBlock, activeTableCol) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_row)) },
                enabled = tableEnabled,
                onClick = { tableMenu = false; viewModel.textTableDeleteRow(activeTableBlock, activeTableRow) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_column)) },
                enabled = tableEnabled,
                onClick = { tableMenu = false; viewModel.textTableDeleteColumn(activeTableBlock, activeTableCol) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.bold_cell)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.setTextTableCellSpanFormat(activeTableBlock, activeTableRow, activeTableCol) { it.copy(bold = !it.bold) } })
            DropdownMenuItem(text = { Text(stringResource(R.string.italic_cell)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.setTextTableCellSpanFormat(activeTableBlock, activeTableRow, activeTableCol) { it.copy(italic = !it.italic) } })
            DropdownMenuItem(text = { Text(stringResource(R.string.merge_with_right)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.mergeTextTableCells(activeTableBlock, activeTableRow, activeTableCol, activeTableRow, activeTableCol + 1) })
            DropdownMenuItem(text = { Text(stringResource(R.string.merge_with_below)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.mergeTextTableCells(activeTableBlock, activeTableRow, activeTableCol, activeTableRow + 1, activeTableCol) })
            DropdownMenuItem(text = { Text(stringResource(R.string.unmerge_cell)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.unmergeTextTableCells(activeTableBlock, activeTableRow, activeTableCol) })
        }
    }
    Box {
        var moreMenu by remember { mutableStateOf(false) }
        FmtIcon(false, enabled, { IconMoreVert() }) { moreMenu = true }
        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.font_size_2)) },
                onClick = { moreMenu = false; actions.onFontSize() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.clear_formatting)) },
                onClick = { moreMenu = false; viewModel.clearRunFormatting(runStart, runEnd, selStart, selEnd) })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.demote_list_item)) },
                enabled = focusedPara >= 0,
                onClick = { moreMenu = false; if (focusedPara >= 0) viewModel.changeListLevel(focusedPara, 1) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.promote_list_item)) },
                enabled = focusedPara >= 0,
                onClick = { moreMenu = false; if (focusedPara >= 0) viewModel.changeListLevel(focusedPara, -1) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.restart_numbering)) },
                enabled = focusedPara >= 0,
                onClick = { moreMenu = false; if (focusedPara >= 0) viewModel.restartNumbering(focusedPara) })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.duplicate_paragraph)) },
                enabled = focusedPara >= 0,
                onClick = { moreMenu = false; viewModel.duplicateParagraph(focusedPara) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_paragraph_up)) },
                enabled = focusedPara > 0,
                onClick = { moreMenu = false; viewModel.moveParagraphUp(focusedPara) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_paragraph_down)) },
                enabled = focusedPara >= 0,
                onClick = { moreMenu = false; viewModel.moveParagraphDown(focusedPara) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_paragraph), color = MaterialTheme.colorScheme.error) },
                enabled = focusedPara >= 0,
                onClick = { moreMenu = false; viewModel.deleteParagraph(focusedPara) })
        }
    }
}

/** Inline character formatting for a text-document run selection. */
internal class TextRunFormatter(
    private val viewModel: OfficeViewModel,
    private val runStart: Int,
    private val runEnd: Int,
    private val selStart: Int,
    private val selEnd: Int,
    override val enabled: Boolean,
) : EditorFormatter {
    override val supported = setOf(
        EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.UNDERLINE, EditorFormat.STRIKETHROUGH,
        EditorFormat.LINK,
    )

    override fun isActive(format: EditorFormat): Boolean = enabled && when (format) {
        EditorFormat.BOLD -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.bold }
        EditorFormat.ITALIC -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.italic }
        EditorFormat.UNDERLINE -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.underline }
        EditorFormat.STRIKETHROUGH -> viewModel.runRangeHasFormat(
            runStart,
            runEnd,
            selStart,
            selEnd) { it.strikethrough }
        else -> false
    }

    override fun toggle(format: EditorFormat) {
        if (!enabled) return
        when (format) {
            EditorFormat.BOLD -> { val t = !isActive(EditorFormat.BOLD); viewModel.applyRunSpanStyle(
                runStart,
                runEnd,
                selStart,
                selEnd) { it.copy(bold = t) } }
            EditorFormat.ITALIC -> { val t = !isActive(EditorFormat.ITALIC); viewModel.applyRunSpanStyle(
                runStart,
                runEnd,
                selStart,
                selEnd) { it.copy(italic = t) } }
            EditorFormat.UNDERLINE -> { val t = !isActive(EditorFormat.UNDERLINE); viewModel.applyRunSpanStyle(
                runStart,
                runEnd,
                selStart,
                selEnd) { it.copy(underline = t) } }
            EditorFormat.STRIKETHROUGH -> { val t = !isActive(EditorFormat.STRIKETHROUGH); viewModel.applyRunSpanStyle(
                runStart,
                runEnd,
                selStart,
                selEnd) { it.copy(strikethrough = t) } }
            else -> {}
        }
    }

    override fun linkContext(): LinkContext? {
        if (!enabled) return null
        val link = viewModel.linkAt(runStart, runEnd, selStart)
        if (link != null) return LinkContext(editing = true, text = link.text, url = link.url)
        if (selStart != selEnd) return LinkContext(
            editing = false,
            text = viewModel.runSelectedText(runStart, runEnd, selStart, selEnd),
            url = "")
        return null
    }

    override fun applyLink(context: LinkContext, text: String, url: String) {
        if (!enabled || url.isBlank()) return
        val link = viewModel.linkAt(runStart, runEnd, selStart)
        val gs: Int; val ge: Int
        if (link != null) { gs = link.gStart; ge = link.gEnd }
        else if (selStart != selEnd) { gs = selStart; ge = selEnd }
        else return
        viewModel.setLink(runStart, runEnd, gs, ge, text.ifBlank { url }, url)
    }

    override fun removeLink(context: LinkContext) {
        val link = viewModel.linkAt(runStart, runEnd, selStart) ?: return
        viewModel.removeLinkInRun(runStart, runEnd, link.gStart, link.gEnd)
    }
}
