package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.fieldDisplayValue
import com.vayunmathur.office.platform.insertFieldInRun
import com.vayunmathur.office.platform.insertHorizontalLine
import com.vayunmathur.office.platform.insertPageBreak
import com.vayunmathur.office.platform.insertTableOfContents

import com.vayunmathur.office.R
/** Insert menu (split from OfficeDocumentMenuBar.kt). */

@Composable
internal fun InsertMenu(
    s: DocumentScreenState,
    viewModel: OfficeViewModel,
    isTextDoc: Boolean,
    focusedPara: Int,
    launchers: DocumentLaunchers,
) {
    if (!isTextDoc) return
    Box {
    TextButton(onClick = { s.insertMenu = true }) { Text(stringResource(R.string.insert)) }
    DropdownMenu(expanded = s.insertMenu, onDismissRequest = { s.insertMenu = false }) {
        @Composable
        fun fieldItem(labelRes: Int, kind: String) {
            DropdownMenuItem(
                text = { Text(stringResource(labelRes)) },
                enabled = s.activeRunStart >= 0,
                onClick = {
                    s.insertMenu = false
                    if (s.activeRunStart >= 0) {
                        viewModel.insertFieldInRun(
                            s.activeRunStart,
                            s.activeRunEnd,
                            s.selStart,
                            kind,
                            viewModel.fieldDisplayValue(kind),
                        )
                    }
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.image_1)) },
            enabled = focusedPara >= 0,
            onClick = { s.insertMenu = false; launchers.imagePicker.launch("image/*") })
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chart)) },
            enabled = focusedPara >= 0,
            onClick = { s.insertMenu = false; s.editingChartBlock = -1; s.showChartEditor = true })
        DropdownMenuItem(
            text = { Text(stringResource(R.string.special_character_1)) },
            enabled = s.activeRunStart >= 0,
            onClick = { s.insertMenu = false; s.showSpecialChars = true })
        fieldItem(R.string.date_field, "date")
        fieldItem(R.string.time_field, "time")
        fieldItem(R.string.page_number, "page-number")
        fieldItem(R.string.page_count, "page-count")
        fieldItem(R.string.file_name, "file-name")
        fieldItem(R.string.meta_author, "author-name")
        fieldItem(R.string.title_field, "title")
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookmark)) },
            enabled = focusedPara >= 0,
            onClick = { s.insertMenu = false; s.showAddBookmark = true })
        DropdownMenuItem(
            text = { Text(stringResource(R.string.footnote)) },
            enabled = focusedPara >= 0,
            onClick = { s.insertMenu = false; s.showFootnote = true })
        DropdownMenuItem(
            text = { Text(stringResource(R.string.comment_1)) },
            enabled = focusedPara >= 0,
            onClick = { s.insertMenu = false; s.showComment = true })
        DropdownMenuItem(
            text = { Text(stringResource(R.string.table_of_contents)) },
            enabled = focusedPara >= 0,
            onClick = { s.insertMenu = false; viewModel.insertTableOfContents(focusedPara) })
        DropdownMenuItem(
            text = { Text(stringResource(R.string.header_footer)) },
            onClick = { s.insertMenu = false; s.showHeaderFooter = true })
        DropdownMenuItem(
            text = { Text(stringResource(R.string.horizontal_line)) },
            enabled = focusedPara >= 0,
            onClick = { s.insertMenu = false; viewModel.insertHorizontalLine(focusedPara) })
        DropdownMenuItem(
            text = { Text(stringResource(R.string.page_break)) },
            enabled = focusedPara >= 0,
            onClick = { s.insertMenu = false; viewModel.insertPageBreak(focusedPara) })
    }
    }
}
