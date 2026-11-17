package com.vayunmathur.office.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfBookmark
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFootnote
import com.vayunmathur.library.ui.odf.OdfMetadata
import com.vayunmathur.library.ui.odf.OdfPageSetup
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.library.ui.odf.addParagraphAfter
import com.vayunmathur.library.ui.odf.deleteParagraph
import com.vayunmathur.library.ui.odf.duplicateParagraph
import com.vayunmathur.library.ui.odf.indentParagraph
import com.vayunmathur.library.ui.odf.moveParagraphDown
import com.vayunmathur.library.ui.odf.moveParagraphUp
import com.vayunmathur.library.ui.odf.outdentParagraph
import com.vayunmathur.library.ui.odf.setParagraphAlignment
import com.vayunmathur.library.ui.odf.setParagraphStyle
import com.vayunmathur.library.ui.odf.toggleListItem
import com.vayunmathur.library.ui.odf.toggleNumberedList
import com.vayunmathur.library.ui.odf.toggleSpanFormat
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.office.R
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

import com.vayunmathur.office.ui.replaceAll
// --- Track changes / tables / paragraph ops (split from OfficeViewModel.kt for file length) ---

fun OfficeViewModel.insertChart(blockIndex: Int, chart: OdfChart) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val content = doc.content.toMutableList()
    val at = (blockIndex + 1).coerceIn(0, content.size)
    content.add(at, OdfContentBlock.Chart(chart))
    updateDocument(doc.copy(content = content))
}

fun OfficeViewModel.updateChart(blockIndex: Int, chart: OdfChart) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    if (doc.content.getOrNull(blockIndex) !is OdfContentBlock.Chart) return
    val content = doc.content.toMutableList()
    content[blockIndex] = OdfContentBlock.Chart(chart)
    updateDocument(doc.copy(content = content))
}

fun OfficeViewModel.addParagraphAfter(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.addParagraphAfter(blockIndex))
}

fun OfficeViewModel.deleteParagraph(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.deleteParagraph(blockIndex) ?: return)
}

fun OfficeViewModel.toggleBold(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(bold = !it.bold) }
fun OfficeViewModel.toggleItalic(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(italic = !it.italic) }
fun OfficeViewModel.toggleUnderline(blockIndex: Int) =
    toggleSpanFormat(blockIndex) { it.copy(underline = !it.underline) }
fun OfficeViewModel.toggleStrikethrough(blockIndex: Int) =
    toggleSpanFormat(blockIndex) { it.copy(strikethrough = !it.strikethrough) }

fun OfficeViewModel.setSpanColor(
    blockIndex: Int,
    color: Long?) = toggleSpanFormat(blockIndex) { it.copy(color = color) }
fun OfficeViewModel.setSpanFontSize(
    blockIndex: Int,
    size: Float) = toggleSpanFormat(blockIndex) { it.copy(fontSize = size) }

fun OfficeViewModel.setParagraphStyle(blockIndex: Int, style: ParagraphStyle) {
    val doc = curText() ?: return
    updateDocument(doc.setParagraphStyle(blockIndex, style) ?: return)
}

fun OfficeViewModel.setParagraphAlignment(blockIndex: Int, alignment: androidx.compose.ui.text.style.TextAlign?) {
    val doc = curText() ?: return
    updateDocument(doc.setParagraphAlignment(blockIndex, alignment) ?: return)
}

fun OfficeViewModel.replaceInDocument(
    search: String,
    replacement: String,
    replaceAll: Boolean,
    matchCase: Boolean = false,
    wholeWord: Boolean = false): Int {
    if (search.isEmpty()) return 0
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return 0
    val pattern = buildSearchPattern(search, matchCase, wholeWord) ?: return 0
    val content = doc.content.toMutableList()
    var count = 0
    for (i in content.indices) {
        val block = content[i] as? OdfContentBlock.Paragraph ?: continue
        val para = block.paragraph
        var changed = false
        val newSpans = para.spans.map { span ->
            val (updated, delta, didChange) = applyReplacementToSpan(
                span,
                pattern,
                replacement,
                replaceAll,
                count,
            )
            count += delta
            if (didChange) changed = true
            updated
        }
        if (changed) content[i] = OdfContentBlock.Paragraph(para.copy(spans = newSpans))
    }
    if (count > 0) updateDocument(doc.copy(content = content))
    return count
}

/** Builds the search regex honoring case/whole-word options, or null when invalid. */
internal fun buildSearchPattern(search: String, matchCase: Boolean, wholeWord: Boolean): Regex? {
    val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
    val boundary = if (wholeWord) "\\b" else ""
    return try { Regex(boundary + Regex.escape(search) + boundary, opts) } catch (_: Exception) { null }
}

/** Applies the replacement to one span. Returns (span, addedCount, changed). */
internal fun applyReplacementToSpan(
    span: OdfSpan,
    pattern: Regex,
    replacement: String,
    replaceAll: Boolean,
    count: Int,
): Triple<OdfSpan, Int, Boolean> {
    if (!replaceAll && count > 0) return Triple(span, 0, false)
    val matches = pattern.findAll(span.text).toList()
    if (matches.isEmpty()) return Triple(span, 0, false)
    if (replaceAll) {
        return Triple(span.copy(text = pattern.replace(span.text) { replacement }), matches.size, true)
    }
    val m = matches.first()
    return Triple(
        span.copy(text = span.text.substring(
            0,
            m.range.first) + replacement + span.text.substring(m.range.last + 1)),
        1,
        true,
    )
}

/** Returns the content block indices that contain a match for the query (B23 find-next navigation). */
fun OfficeViewModel.findMatchBlocks(search: String, matchCase: Boolean = false, wholeWord: Boolean = false): List<Int> {
    if (search.isEmpty()) return emptyList()
    val doc =
        (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return emptyList()
    val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
    val boundary = if (wholeWord) "\\b" else ""
    val pattern = try { Regex(
        boundary + Regex.escape(search) + boundary,
        opts) } catch (_: Exception) { return emptyList() }
    val result = mutableListOf<Int>()
    doc.content.forEachIndexed { i, block ->
        if (block is OdfContentBlock.Paragraph && block.paragraph.spans.any { pattern.containsMatchIn(it.text) }) {
            result.add(i)
        }
    }
    return result
}

fun OfficeViewModel.duplicateParagraph(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.duplicateParagraph(blockIndex) ?: return)
}

fun OfficeViewModel.moveParagraphUp(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.moveParagraphUp(blockIndex) ?: return)
}

fun OfficeViewModel.moveParagraphDown(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.moveParagraphDown(blockIndex) ?: return)
}

fun OfficeViewModel.toggleListItem(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.toggleListItem(blockIndex) ?: return)
}

fun OfficeViewModel.toggleNumberedList(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.toggleNumberedList(blockIndex) ?: return)
}

fun OfficeViewModel.indentParagraph(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.indentParagraph(blockIndex) ?: return)
}

fun OfficeViewModel.outdentParagraph(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.outdentParagraph(blockIndex) ?: return)
}

fun OfficeViewModel.insertTable(blockIndex: Int, rows: Int, cols: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val content = doc.content.toMutableList()
    val tableRows = (0 until rows).map {
        val cells = (0 until cols).map { OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan(text = ""))))) }
        OdfTableRow(cells)
    }
    content.add(blockIndex + 1, OdfContentBlock.Table(OdfTable("Table", emptyList(), tableRows)))
    updateDocument(doc.copy(content = content))
}

fun OfficeViewModel.insertPageBreak(blockIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val content = doc.content.toMutableList()
    content.add(blockIndex + 1, OdfContentBlock.PageBreak)
    updateDocument(doc.copy(content = content))
}

fun OfficeViewModel.insertHyperlink(blockIndex: Int, text: String, url: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val content = doc.content.toMutableList()
    val linkSpan = OdfSpan(text = text, href = url, underline = true, color = 0xFF0066CCL)
    content.add(blockIndex + 1, OdfContentBlock.Paragraph(OdfParagraph(listOf(linkSpan))))
    updateDocument(doc.copy(content = content))
}

fun OfficeViewModel.addBookmark(name: String, contentIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val bookmarks = doc.bookmarks.toMutableList()
    bookmarks.add(OdfBookmark(name, contentIndex))
    updateDocument(doc.copy(bookmarks = bookmarks))
}

fun OfficeViewModel.removeBookmark(name: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val bookmarks = doc.bookmarks.filter { it.name != name }
    updateDocument(doc.copy(bookmarks = bookmarks))
}

/** Edit document metadata (G47). */
fun OfficeViewModel.updateMetadata(transform: (OdfMetadata) -> OdfMetadata) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document ?: return
    val newDoc = when (doc) {
        is OdfDocument.TextDocument -> doc.copy(metadata = transform(doc.metadata))
        is OdfDocument.Spreadsheet -> doc.copy(metadata = transform(doc.metadata))
        is OdfDocument.Presentation -> doc.copy(metadata = transform(doc.metadata))
        is OdfDocument.Drawing -> doc.copy(metadata = transform(doc.metadata))
    }
    updateDocument(newDoc)
}

internal fun OfficeViewModel.toggleSpanFormat(blockIndex: Int, transform: (OdfSpan) -> OdfSpan) {
    val doc = curText() ?: return
    updateDocument(doc.toggleSpanFormat(blockIndex, transform) ?: return)
}
