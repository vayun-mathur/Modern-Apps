package com.vayunmathur.office.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.ui.odf.EpubExporter
import com.vayunmathur.library.ui.odf.HtmlOdfConverter
import com.vayunmathur.library.ui.odf.LatexExporter
import com.vayunmathur.library.ui.odf.MarkdownOdfConverter
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfSerializer
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OoxmlExporter
import com.vayunmathur.library.ui.odf.PdfExporter
import com.vayunmathur.library.ui.odf.RtfOdfConverter
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.office.R
import com.vayunmathur.office.data.odf.OdfMath
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// --- Export (split from OfficeViewModel.kt for file length) ---

/** Quote a CSV cell when it contains the delimiter, quotes, or newlines. */
private fun escapeCsvCell(text: String, delimiter: Char): String {
    if (needsCsvQuoting(text, delimiter)) {
        return "\"${text.replace("\"", "\"\"")}\""
    }
    return text
}

/** True when a CSV cell needs quoting. */
private fun needsCsvQuoting(text: String, delimiter: Char): Boolean =
    text.contains(delimiter) || text.contains('"') || text.contains('\n') || text.contains('\r')

fun OfficeViewModel.exportCsv(delimiter: Char = ','): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return ""
    val sb = StringBuilder()
    val sheet = doc.sheets.firstOrNull() ?: return ""
    for (row in sheet.rows) {
        sb.appendLine(row.cells.joinToString(delimiter.toString()) { cell ->
            escapeCsvCell(cell.text, delimiter)
        })
    }
    return sb.toString()
}

/** Markdown export for text documents (empty for other types). */
fun OfficeViewModel.exportMarkdown(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ""
    return MarkdownOdfConverter.odfToMarkdown(doc)
}

/** Best-effort OOXML (docx/xlsx/pptx) export. */
fun OfficeViewModel.exportOoxml(): ByteArray {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document ?: return ByteArray(0)
    return OoxmlExporter.export(doc)
}

fun OfficeViewModel.ooxmlExtension(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document ?: return "docx"
    return OoxmlExporter.extensionFor(doc)
}

/** HTML export for text documents (empty for other types). */
fun OfficeViewModel.exportHtml(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ""
    return HtmlOdfConverter.odfToHtml(doc)
}

/** RTF export for text documents (empty for other types). */
fun OfficeViewModel.exportRtf(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ""
    return RtfOdfConverter.odfToRtf(doc)
}

/** LaTeX export for text documents (empty for other types). */
fun OfficeViewModel.exportLatex(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ""
    return LatexExporter.export(doc)
}

/** EPUB export for text documents (empty for other types). */
fun OfficeViewModel.exportEpub(): ByteArray {
    val doc =
        (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ByteArray(0)
    return EpubExporter.export(doc)
}

/** PDF export for text documents (empty for other types). */
fun OfficeViewModel.exportPdf(): ByteArray {
    val doc =
        (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ByteArray(0)
    return PdfExporter.export(doc)
}

/** Flat ODF (.fodt/.fods/.fodp) export (K75). */
fun OfficeViewModel.exportFlat(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document ?: return ""
    return OdfSerializer.serializeFlat(doc)
}

fun OfficeViewModel.exportAsPlainText(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document ?: return ""
    val sb = StringBuilder()
    when (doc) {
        is OdfDocument.TextDocument -> {
            for (block in doc.content) appendTextBlockPlain(sb, block)
        }
        is OdfDocument.Spreadsheet -> appendSheetPlain(sb, doc)
        is OdfDocument.Presentation -> {
            for (slide in doc.slides) {
                sb.appendLine("=== ${slide.name} ===")
                appendSlideElementsPlain(sb, slide.elements)
                sb.appendLine()
            }
        }
        is OdfDocument.Drawing -> {
            for (page in doc.pages) {
                sb.appendLine("=== ${page.name} ===")
                appendSlideElementsPlain(sb, page.elements)
                sb.appendLine()
            }
        }
    }
    return sb.toString()
}

/** One text-document block as plain text. */
private fun appendTextBlockPlain(sb: StringBuilder, block: OdfContentBlock) {
    when (block) {
        is OdfContentBlock.Paragraph -> {
            sb.appendLine(block.paragraph.spans.joinToString("") { it.text })
        }
        is OdfContentBlock.Table -> {
            for (row in block.table.rows) {
                val line = row.cells.filterNot { it.isCovered }.joinToString("\t") { cell ->
                    cell.paragraphs.joinToString(" ") { p ->
                        p.spans.joinToString("") { it.text }
                    }
                }
                sb.appendLine(line)
            }
        }
        is OdfContentBlock.PageBreak -> sb.appendLine("---")
        is OdfContentBlock.Image -> sb.appendLine("[Image]")
        is OdfContentBlock.Chart -> sb.appendLine("[Chart]")
        is OdfContentBlock.Formula -> {
            val text = OdfMath.parse(block.mathml)?.let { OdfMath.toText(it) }.orEmpty()
            sb.appendLine("[Formula] $text")
        }
        is OdfContentBlock.TableOfContents -> {
            sb.appendLine(block.title)
            for (entry in block.entries) {
                sb.appendLine(entry.spans.joinToString("") { it.text })
            }
        }
        is OdfContentBlock.SectionStart, OdfContentBlock.SectionEnd -> {}
    }
}

/** One sheet as plain text. */
private fun appendSheetPlain(sb: StringBuilder, doc: OdfDocument.Spreadsheet) {
    for (sheet in doc.sheets) {
        sb.appendLine("=== ${sheet.name} ===")
        for (row in sheet.rows) {
            val line = row.cells.filterNot { it.isCovered }.joinToString("\t") { it.text }
            sb.appendLine(line)
        }
        sb.appendLine()
    }
}

/** Slide/drawing elements as plain-text paragraphs. */
private fun appendSlideElementsPlain(sb: StringBuilder, elements: List<OdfSlideElement>) {
    for (el in elements) {
        when (el) {
            is OdfSlideElement.Frame -> {
                for (p in el.frame.paragraphs) {
                    sb.appendLine(p.spans.joinToString("") { it.text })
                }
            }
            is OdfSlideElement.Shape -> {
                for (p in el.shape.text) {
                    sb.appendLine(p.spans.joinToString("") { it.text })
                }
            }
        }
    }
}
