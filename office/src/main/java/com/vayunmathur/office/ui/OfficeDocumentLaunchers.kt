package com.vayunmathur.office.ui

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.save
import com.vayunmathur.office.platform.exportAsPlainText
import com.vayunmathur.office.platform.exportCsv
import com.vayunmathur.office.platform.exportEpub
import com.vayunmathur.office.platform.exportFlat
import com.vayunmathur.office.platform.exportHtml
import com.vayunmathur.office.platform.exportLatex
import com.vayunmathur.office.platform.exportMarkdown
import com.vayunmathur.office.platform.exportOoxml
import com.vayunmathur.office.platform.exportPdf
import com.vayunmathur.office.platform.exportRtf

/**
 * All activity-result launchers used by [DocumentScreen] (save/export/import/replace flows).
 *
 * Hoisted verbatim from `DocumentScreen`'s locals (split for file length); captures became
 * explicit parameters, behavior identical.
 */
class DocumentLaunchers(
    val saveAs: ManagedActivityResultLauncher<String, Uri?>,
    val csvExport: ManagedActivityResultLauncher<String, Uri?>,
    val imagePicker: ManagedActivityResultLauncher<String, Uri?>,
    val flatExport: ManagedActivityResultLauncher<String, Uri?>,
    val markdownExport: ManagedActivityResultLauncher<String, Uri?>,
    val txtExport: ManagedActivityResultLauncher<String, Uri?>,
    val tsvExport: ManagedActivityResultLauncher<String, Uri?>,
    val ooxmlExport: ManagedActivityResultLauncher<String, Uri?>,
    val htmlExport: ManagedActivityResultLauncher<String, Uri?>,
    val rtfExport: ManagedActivityResultLauncher<String, Uri?>,
    val latexExport: ManagedActivityResultLauncher<String, Uri?>,
    val epubExport: ManagedActivityResultLauncher<String, Uri?>,
    val pdfExport: ManagedActivityResultLauncher<String, Uri?>,
    val replaceImage: ManagedActivityResultLauncher<String, Uri?>,
)

private fun writeExportText(context: android.content.Context, uri: Uri, text: String) {
    if (text.isNotEmpty()) {
        context.contentResolver.openOutputStream(uri)?.writer()?.use { w -> w.write(text) }
    }
}

private fun writeExportBytes(context: android.content.Context, uri: Uri, bytes: ByteArray) {
    if (bytes.isNotEmpty()) {
        context.contentResolver.openOutputStream(uri)?.use { o -> o.write(bytes) }
    }
}

@Composable
fun rememberDocumentLaunchers(
    viewModel: OfficeViewModel,
    holder: DocumentScreenState,
    onPickImageBytes: (name: String, bytes: ByteArray) -> Unit,
): DocumentLaunchers {
    val context = LocalContext.current
    val saveAsLauncher = rememberSaveAsLauncher(viewModel)
    val imagePickerLauncher = rememberImagePickerLauncher(context, onPickImageBytes)
    val replaceImageLauncher = rememberReplaceImageLauncher(context, holder)
    val flatExportLauncher = rememberFlatExportLauncher(context, viewModel)
    val sheetExports = rememberSheetExportLaunchers(context, viewModel)
    val docExports = rememberDocExportLaunchers(context, viewModel)
    return DocumentLaunchers(
        saveAsLauncher,
        sheetExports.csv,
        imagePickerLauncher,
        flatExportLauncher,
        docExports.markdown,
        docExports.txt,
        sheetExports.tsv,
        docExports.ooxml,
        docExports.html,
        docExports.rtf,
        docExports.latex,
        docExports.epub,
        docExports.pdf,
        replaceImageLauncher,
    )
}

/** Save-as launcher. */
@Composable
private fun rememberSaveAsLauncher(viewModel: OfficeViewModel) =
    rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { viewModel.save(it) } }

/** Reads picked image bytes and forwards them. */
@Composable
private fun rememberImagePickerLauncher(
    context: android.content.Context,
    onPickImageBytes: (name: String, bytes: ByteArray) -> Unit,
) = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    uri?.let {
        try {
            val bytes = context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } ?: return@let
            val name = it.lastPathSegment?.substringAfterLast('/') ?: "image.png"
            onPickImageBytes(name, bytes)
        } catch (_: Exception) {}
    }
}

/** Replace-image launcher (invokes the pending replace action). */
@Composable
private fun rememberReplaceImageLauncher(
    context: android.content.Context,
    holder: DocumentScreenState,
) = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    val fn = holder.pendingReplace
    holder.pendingReplace = null
    uri?.let {
        try {
            val bytes = context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } ?: return@let
            val name = it.lastPathSegment?.substringAfterLast('/') ?: "image.png"
            fn?.invoke(name, bytes)
        } catch (_: Exception) {}
    }
}

/** Flat-ODF export launcher. */
@Composable
private fun rememberFlatExportLauncher(context: android.content.Context, viewModel: OfficeViewModel) =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        uri?.let {
            val xml = viewModel.exportFlat()
            if (xml.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(xml) }
        }
    }

/** CSV/TSV sheet export launchers. */
private class SheetExports(val csv: ManagedDocLauncher, val tsv: ManagedDocLauncher)

private typealias ManagedDocLauncher =
    androidx.activity.compose.ManagedActivityResultLauncher<String, android.net.Uri?>

/** CSV + TSV export launchers. */
@Composable
private fun rememberSheetExportLaunchers(
    context: android.content.Context,
    viewModel: OfficeViewModel,
): SheetExports {
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            val csvText = viewModel.exportCsv()
            context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(csvText) }
        }
    }
    val tsv =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/tab-separated-values")) { uri ->
            uri?.let { writeExportText(context, it, viewModel.exportCsv('\t')) }
        }
    return SheetExports(csv, tsv)
}

/** Document export launchers (ooxml/html/rtf/latex/epub/pdf/markdown/txt). */
private class DocExports(
    val markdown: ManagedDocLauncher,
    val txt: ManagedDocLauncher,
    val ooxml: ManagedDocLauncher,
    val html: ManagedDocLauncher,
    val rtf: ManagedDocLauncher,
    val latex: ManagedDocLauncher,
    val epub: ManagedDocLauncher,
    val pdf: ManagedDocLauncher,
)

/** Text + binary document export launchers. */
@Composable
private fun rememberDocExportLaunchers(
    context: android.content.Context,
    viewModel: OfficeViewModel,
): DocExports {
    val markdown =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            uri?.let { writeExportText(context, it, viewModel.exportMarkdown()) }
        }
    val txt =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { writeExportText(context, it, viewModel.exportAsPlainText()) }
        }
    val ooxml =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            uri?.let { writeExportBytes(context, it, viewModel.exportOoxml()) }
        }
    val html =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
            uri?.let { writeExportText(context, it, viewModel.exportHtml()) }
        }
    val rtf =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/rtf")) { uri ->
            uri?.let { writeExportText(context, it, viewModel.exportRtf()) }
        }
    val latex =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-tex")) { uri ->
            uri?.let { writeExportText(context, it, viewModel.exportLatex()) }
        }
    val epub =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri ->
            uri?.let { writeExportBytes(context, it, viewModel.exportEpub()) }
        }
    val pdf =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            uri?.let { writeExportBytes(context, it, viewModel.exportPdf()) }
        }
    return DocExports(markdown, txt, ooxml, html, rtf, latex, epub, pdf)
}
