package com.vayunmathur.office.ui

import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.office.data.odf.OdfMath
import com.vayunmathur.library.ui.odf.OdfMetadata
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle

import com.vayunmathur.office.platform.save
import com.vayunmathur.office.R
@Composable
internal fun MetadataDialog(metadata: OdfMetadata, onSave: (OdfMetadata) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(metadata.title ?: "") }
    var author by remember { mutableStateOf(metadata.author ?: "") }
    var subject by remember { mutableStateOf(metadata.subject ?: "") }
    var description by remember { mutableStateOf(metadata.description ?: "") }
    var keywords by remember { mutableStateOf(metadata.keywords.joinToString(", ")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.document_info)) },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                MetaTextFields(
                    title = title, onTitle = { title = it },
                    author = author, onAuthor = { author = it },
                    subject = subject, onSubject = { subject = it },
                    description = description, onDescription = { description = it },
                    keywords = keywords, onKeywords = { keywords = it })
                Spacer(Modifier.height(MetaSectionSpacing))
                MetaStatRows(metadata)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(buildEditedMetadata(metadata, title, author, subject, description, keywords))
                onDismiss()
            }) { Text(stringResource(UiR.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } })
}

/** Metadata with dialog edits applied. */
private fun buildEditedMetadata(
    metadata: OdfMetadata,
    title: String,
    author: String,
    subject: String,
    description: String,
    keywords: String,
): OdfMetadata {
    return metadata.copy(
        title = title.ifBlank { null },
        author = author.ifBlank { null },
        subject = subject.ifBlank { null },
        description = description.ifBlank { null },
        keywords = keywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    )
}

/** Editable metadata text fields. */
@Composable
private fun MetaTextFields(
    title: String, onTitle: (String) -> Unit,
    author: String, onAuthor: (String) -> Unit,
    subject: String, onSubject: (String) -> Unit,
    description: String, onDescription: (String) -> Unit,
    keywords: String, onKeywords: (String) -> Unit,
) {
    TextField(
        value = title,
        onValueChange = onTitle,
        label = { Text(stringResource(R.string.meta_title)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(MetaFieldSpacing))
    TextField(
        value = author,
        onValueChange = onAuthor,
        label = { Text(stringResource(R.string.meta_author)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(MetaFieldSpacing))
    TextField(
        value = subject,
        onValueChange = onSubject,
        label = { Text(stringResource(R.string.meta_subject)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(MetaFieldSpacing))
    TextField(
        value = description,
        onValueChange = onDescription,
        label = { Text(stringResource(R.string.meta_description)) },
        modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(MetaFieldSpacing))
    TextField(
        value = keywords,
        onValueChange = onKeywords,
        label = { Text(stringResource(R.string.meta_keywords)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth())
}

/** Read-only metadata stat rows. */
@Composable
private fun MetaStatRows(metadata: OdfMetadata) {
    metadata.creationDate?.let { MetadataRow(stringResource(R.string.meta_created), it) }
    metadata.modifiedDate?.let { MetadataRow(stringResource(R.string.meta_modified), it) }
    metadata.pageCount?.let { MetadataRow(stringResource(R.string.meta_pages), it.toString()) }
    metadata.wordCount?.let { MetadataRow(stringResource(R.string.meta_words), it.toString()) }
    metadata.fileSize?.let { MetadataRow("File Size", formatFileSize(it)) }
}

@Composable private fun MetadataRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) { Text(
        "$label: ",
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.bodyMedium); Text(value, style = MaterialTheme.typography.bodyMedium) }
}

private val MetaFieldSpacing = 6.dp
private val MetaSectionSpacing = 8.dp
private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1024L * 1024L

internal fun formatFileSize(bytes: Long): String = when {
    bytes < BYTES_PER_KB -> "$bytes B"
    bytes < BYTES_PER_MB -> "${bytes / BYTES_PER_KB} KB"
    else -> "${"%.1f".format(bytes / BYTES_PER_MB.toDouble())} MB"
}

// --- Print ---

internal fun printDocument(activity: ComponentActivity, document: OdfDocument) {
    val printManager = activity.getSystemService(android.content.Context.PRINT_SERVICE) as? PrintManager ?: return
    val html = documentToHtml(document)
    val webView = WebView(activity)
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            @Suppress("DEPRECATION")
            val printAdapter = webView.createPrintDocumentAdapter(document.title)
            printManager.print(
                document.title,
                printAdapter,
                PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        }
    }
    webView.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
}

private fun StringBuilder.appendEscaped(text: String) {
    append(text.replace("&", "&amp;").replace("<", "&lt;"))
}

private fun headingTag(style: ParagraphStyle): String = when (style) {
    ParagraphStyle.HEADING1 -> "h1"
    ParagraphStyle.HEADING2 -> "h2"
    ParagraphStyle.HEADING3 -> "h3"
    ParagraphStyle.HEADING4 -> "h4"
    ParagraphStyle.LIST_ITEM -> "li"
    else -> "p"
}

private fun StringBuilder.appendSpanHtml(span: OdfSpan) {
    var s = span.text.replace("&", "&amp;").replace("<", "&lt;")
    if (span.bold) s = "<b>$s</b>"
    if (span.italic) s = "<i>$s</i>"
    if (span.underline) s = "<u>$s</u>"
    if (span.strikethrough) s = "<s>$s</s>"
    if (span.href != null) s = """<a href="${span.href}">$s</a>"""
    append(s)
}

private fun StringBuilder.appendParagraphs(paragraphs: List<OdfParagraph>) {
    for (p in paragraphs) {
        append("<p>")
        for (s in p.spans) appendEscaped(s.text)
        append("</p>")
    }
}

/** One spreadsheet cell as an HTML td. */
private fun StringBuilder.appendSheetCell(cell: OdfCell) {
    if (cell.isCovered) return
    append("<td")
    if (cell.spannedColumns > 1) {
        append(""" colspan="${cell.spannedColumns}"""")
    }
    append(">")
    appendEscaped(cell.text)
    append("</td>")
}

/** One ODF table as an HTML table. */
private fun StringBuilder.appendTableHtml(table: OdfTable) {
    append("<table>")
    for (row in table.rows) {
        append("<tr>")
        for (cell in row.cells) appendCellHtml(cell)
        append("</tr>")
    }
    append("</table>")
}

/** One ODF table cell as an HTML td. */
private fun StringBuilder.appendCellHtml(cell: OdfTableCell) {
    if (cell.isCovered) return
    append("<td")
    if (cell.colSpan > 1) append(""" colspan="${cell.colSpan}"""")
    if (cell.rowSpan > 1) append(""" rowspan="${cell.rowSpan}"""")
    append(">")
    for (para in cell.paragraphs) {
        for (span in para.spans) appendEscaped(span.text)
    }
    append("</td>")
}

private fun StringBuilder.appendTextBlock(block: OdfContentBlock) {
    when (block) {
        is OdfContentBlock.Paragraph -> {
            val tag = headingTag(block.paragraph.style)
            append("<$tag>")
            for (span in block.paragraph.spans) appendSpanHtml(span)
            append("</$tag>")
        }
        is OdfContentBlock.Table -> appendTableHtml(block.table)
        is OdfContentBlock.Image -> append("<p>[Image]</p>")
        is OdfContentBlock.Chart -> append("<p>[Chart]</p>")
        is OdfContentBlock.Formula -> {
            val text = OdfMath.parse(block.mathml)?.let { OdfMath.toText(it) }.orEmpty()
            append("<p><i>")
            appendEscaped(text)
            append("</i></p>")
        }
        is OdfContentBlock.PageBreak -> append("""<hr style="page-break-after:always">""")
        is OdfContentBlock.TableOfContents -> {
            append("<h2>")
            appendEscaped(block.title)
            append("</h2>")
            for (entry in block.entries) {
                append("<p>")
                appendEscaped(entry.spans.joinToString("") { it.text })
                append("</p>")
            }
        }
        is OdfContentBlock.SectionStart -> {
            if (block.columnCount > 1) {
                append("""<div style="column-count:${block.columnCount}">""")
            } else {
                append("<div>")
            }
        }
        is OdfContentBlock.SectionEnd -> append("</div>")
    }
}

private fun StringBuilder.appendSlideElements(elements: List<OdfSlideElement>) {
    for (el in elements) {
        when (el) {
            is OdfSlideElement.Frame -> appendParagraphs(el.frame.paragraphs)
            is OdfSlideElement.Shape -> appendParagraphs(el.shape.text)
        }
    }
}

internal fun documentToHtml(document: OdfDocument): String {
    val sb = StringBuilder()
    sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>")
    sb.append("body{font-family:sans-serif;padding:16px}")
    sb.append("table{border-collapse:collapse;width:100%;margin:8px 0}")
    sb.append("td,th{border:1px solid #ccc;padding:6px 8px}")
    sb.append("h1,h2,h3,h4{margin:8px 0}")
    sb.append(".slide{border:1px solid #ccc;padding:24px;margin:16px 0;background:#f9f9f9}")
    sb.append("</style></head><body>")
    when (document) {
        is OdfDocument.TextDocument -> {
            for (block in document.content) sb.appendTextBlock(block)
        }
        is OdfDocument.Spreadsheet -> {
            for (sheet in document.sheets) {
                sb.append("<h2>")
                sb.appendEscaped(sheet.name)
                sb.append("</h2><table>")
                for (row in sheet.rows) {
                    sb.append("<tr>")
                    for (cell in row.cells) sb.appendSheetCell(cell)
                    sb.append("</tr>")
                }
                sb.append("</table>")
            }
        }
        is OdfDocument.Presentation -> {
            for (slide in document.slides) {
                sb.append("""<div class="slide"><b>""")
                sb.appendEscaped(slide.name)
                sb.append("</b>")
                sb.appendSlideElements(slide.elements)
                sb.append("</div>")
            }
        }
        is OdfDocument.Drawing -> {
            for (page in document.pages) {
                sb.append("""<div class="slide"><b>""")
                sb.appendEscaped(page.name)
                sb.append("</b>")
                sb.appendSlideElements(page.elements)
                sb.append("</div>")
            }
        }
    }
    sb.append("</body></html>")
    return sb.toString()
}
