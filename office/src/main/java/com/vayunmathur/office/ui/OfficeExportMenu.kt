package com.vayunmathur.office.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.exportAsPlainText
import kotlinx.coroutines.CoroutineScope

import com.vayunmathur.office.R
/** Export submenu (split from OfficeDocumentMenuBar.kt). */

@Composable
internal fun ExportMenu(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    isTextDoc: Boolean,
    isSpreadsheet: Boolean,
    isPresentation: Boolean,
    launchers: DocumentLaunchers,
) {
    val context = LocalContext.current
    ExportDropdown(s, document, viewModel, isTextDoc, isSpreadsheet, isPresentation, launchers, context)
}

/** Export dropdown content. */
@Composable
private fun ExportDropdown(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    isTextDoc: Boolean,
    isSpreadsheet: Boolean,
    isPresentation: Boolean,
    launchers: DocumentLaunchers,
    context: android.content.Context,
) {
    DropdownMenu(expanded = s.exportMenu, onDismissRequest = { s.exportMenu = false }) {
            val baseName = document.title.substringBeforeLast('.').ifBlank { "document" }
            fun warnExport(run: () -> Unit) {
                s.exportMenu = false
                s.exportWarning = run
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_as_text)) },
                onClick = {
                    s.exportMenu = false
                    val t = viewModel.exportAsPlainText()
                    if (t.isNotEmpty()) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, t)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.flat_odf)) },
                onClick = {
                    s.exportMenu = false
                    val ext = when {
                        isTextDoc -> ".fodt"
                        isSpreadsheet -> ".fods"
                        isPresentation -> ".fodp"
                        else -> ".fodg"
                    }
                    launchers.flatExport.launch(document.title.substringBeforeLast('.') + ext)
                },
            )
            TextExportItems(isTextDoc, baseName, launchers, ::warnExport)
            SheetExportItems(isSpreadsheet, baseName, launchers, ::warnExport)
            PresentationExportItems(isPresentation, baseName, launchers, ::warnExport)
        }
}

/** Text-document export items. */
@Composable
private fun TextExportItems(
    isTextDoc: Boolean,
    baseName: String,
    launchers: DocumentLaunchers,
    warnExport: (() -> Unit) -> Unit,
) {
    if (!isTextDoc) return
    DropdownMenuItem(
        text = { Text(stringResource(R.string.word_docx)) },
        onClick = { warnExport { launchers.ooxmlExport.launch("$baseName.docx") } },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.pdf_pdf)) },
        onClick = { warnExport { launchers.pdfExport.launch("$baseName.pdf") } },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.html_html)) },
        onClick = { warnExport { launchers.htmlExport.launch("$baseName.html") } },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.rich_text_rtf)) },
        onClick = { warnExport { launchers.rtfExport.launch("$baseName.rtf") } },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.epub_epub)) },
        onClick = { warnExport { launchers.epubExport.launch("$baseName.epub") } },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.latex_tex)) },
        onClick = { warnExport { launchers.latexExport.launch("$baseName.tex") } },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.markdown_md)) },
        onClick = { warnExport { launchers.markdownExport.launch("$baseName.md") } },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.text_txt)) },
        onClick = { warnExport { launchers.txtExport.launch("$baseName.txt") } },
    )
}

/** Spreadsheet export items. */
@Composable
private fun SheetExportItems(
    isSpreadsheet: Boolean,
    baseName: String,
    launchers: DocumentLaunchers,
    warnExport: (() -> Unit) -> Unit,
) {
    if (!isSpreadsheet) return
    DropdownMenuItem(
        text = { Text(stringResource(R.string.excel_xlsx)) },
        onClick = { warnExport { launchers.ooxmlExport.launch("$baseName.xlsx") } },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.csv)) },
        onClick = { warnExport { launchers.csvExport.launch("$baseName.csv") } },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.tsv)) },
        onClick = { warnExport { launchers.tsvExport.launch("$baseName.tsv") } },
    )
}

/** Presentation export items. */
@Composable
private fun PresentationExportItems(
    isPresentation: Boolean,
    baseName: String,
    launchers: DocumentLaunchers,
    warnExport: (() -> Unit) -> Unit,
) {
    if (!isPresentation) return
    DropdownMenuItem(
        text = { Text(stringResource(R.string.powerpoint_pptx)) },
        onClick = { warnExport { launchers.ooxmlExport.launch("$baseName.pptx") } },
    )
}
