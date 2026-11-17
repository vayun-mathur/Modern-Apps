package com.vayunmathur.office.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.library.ui.odf.applyRunSpanStyle
import com.vayunmathur.library.ui.odf.applySpanStyleToRange
import com.vayunmathur.library.ui.odf.changeListLevel
import com.vayunmathur.library.ui.odf.clearRunFormatting
import com.vayunmathur.library.ui.odf.handleListBackspace
import com.vayunmathur.library.ui.odf.handleListEnter
import com.vayunmathur.library.ui.odf.insertHorizontalLine
import com.vayunmathur.library.ui.odf.insertTextInRun
import com.vayunmathur.library.ui.odf.mutateRunParagraphs
import com.vayunmathur.library.ui.odf.rangeHasFormat
import com.vayunmathur.library.ui.odf.restartNumbering
import com.vayunmathur.library.ui.odf.runParagraphIndexAt
import com.vayunmathur.library.ui.odf.runRangeHasFormat
import com.vayunmathur.library.ui.odf.setCheckboxChecked
import com.vayunmathur.library.ui.odf.setLinkInRun
import com.vayunmathur.library.ui.odf.toggleCheckbox
import com.vayunmathur.library.ui.odf.updateParagraphRun
import com.vayunmathur.library.ui.odf.updateParagraphText
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

// --- Text document editing (split from OfficeViewModel.kt for file length) ---

internal fun OfficeViewModel.curText(): OdfDocument.TextDocument? =
    (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument

fun OfficeViewModel.updateParagraphText(blockIndex: Int, newText: String) {
    val doc = curText() ?: return
    updateDocument(doc.updateParagraphText(blockIndex, newText) ?: return)
}

/** Applies a span transform to the character range [start, end). If the range is empty, applies to the whole
 * paragraph. */
fun OfficeViewModel.applySpanStyleToRange(blockIndex: Int, start: Int, end: Int, transform: (OdfSpan) -> OdfSpan) {
    val doc = curText() ?: return
    updateDocument(doc.applySpanStyleToRange(blockIndex, start, end, transform) ?: return)
}

/** True if every character in [start, end) (or the whole paragraph when empty) satisfies [predicate]. */
fun OfficeViewModel.rangeHasFormat(blockIndex: Int, start: Int, end: Int, predicate: (OdfSpan) -> Boolean): Boolean =
    curText()?.rangeHasFormat(blockIndex, start, end, predicate) ?: false

internal fun OfficeViewModel.spansToChars(spans: List<OdfSpan>): MutableList<OdfSpan> {
    val out = ArrayList<OdfSpan>()
    for (span in spans) for (ch in span.text) out.add(span.copy(text = ch.toString()))
    return out
}

internal fun OfficeViewModel.charsToSpans(chars: List<OdfSpan>): List<OdfSpan> {
    if (chars.isEmpty()) return listOf(OdfSpan(text = ""))
    val out = ArrayList<OdfSpan>()
    var current = chars[0]
    val sb = StringBuilder(current.text)
    for (i in 1 until chars.size) {
        val c = chars[i]
        if (c.copy(text = "") == current.copy(text = "")) {
            sb.append(c.text)
        } else {
            out.add(current.copy(text = sb.toString()))
            current = c
            sb.setLength(0)
            sb.append(c.text)
        }
    }
    out.add(current.copy(text = sb.toString()))
    return out
}

// --- Continuous (multi-paragraph run) editing ---

internal fun OfficeViewModel.runParas(start: Int, endInclusive: Int): List<OdfParagraph>? {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return null
    if (start < 0 || endInclusive >= doc.content.size || start > endInclusive) return null
    val list = ArrayList<OdfParagraph>()
    for (i in start..endInclusive) {
        val b = doc.content[i] as? OdfContentBlock.Paragraph ?: return null
        list.add(b.paragraph)
    }
    return list
}

internal fun OfficeViewModel.paraLens(paras: List<OdfParagraph>) = paras.map { p -> p.spans.sumOf { it.text.length } }

internal fun OfficeViewModel.runLocate(lens: List<Int>, pos: Int): Pair<Int, Int> {
    var rem = pos
    for (i in lens.indices) {
        if (rem <= lens[i]) return i to rem
        rem -= lens[i] + 1 // consume paragraph chars + separator
        if (rem < 0) return i to lens[i]
    }
    return lens.lastIndex.coerceAtLeast(0) to (lens.lastOrNull() ?: 0)
}

/** Edits a run of consecutive paragraphs [start, endInclusive] as one continuous text (paragraphs joined by '\n'). */
fun OfficeViewModel.updateParagraphRun(start: Int, endInclusive: Int, newText: String) {
    val doc = curText() ?: return
    updateDocument(doc.updateParagraphRun(start, endInclusive, newText) ?: return)
}

/** Smart Enter: splits/continues a list line, or exits an empty list item. Returns the new caret. */
fun OfficeViewModel.handleListEnter(start: Int, endInclusive: Int, gPos: Int): Int? {
    val doc = curText() ?: return null
    val (newDoc, caret) = doc.handleListEnter(start, endInclusive, gPos) ?: return null
    updateDocument(newDoc)
    return caret
}

/** Smart Backspace at the start of a list item: outdent/remove the marker. Returns the new caret. */
fun OfficeViewModel.handleListBackspace(start: Int, endInclusive: Int, gPos: Int): Int? {
    val doc = curText() ?: return null
    val (newDoc, caret) = doc.handleListBackspace(start, endInclusive, gPos) ?: return null
    updateDocument(newDoc)
    return caret
}

/** Toggles a checklist item on/off for a paragraph. */
fun OfficeViewModel.toggleCheckbox(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.toggleCheckbox(blockIndex) ?: return)
}

/** Sets the checked state of a checklist item (tapping the box). */
fun OfficeViewModel.setCheckboxChecked(blockIndex: Int, checked: Boolean) {
    val doc = curText() ?: return
    updateDocument(doc.setCheckboxChecked(blockIndex, checked) ?: return)
}

/** Applies a span transform across a (possibly multi-paragraph) selection within a run. Empty selection = caret's
 * whole paragraph. */
fun OfficeViewModel.applyRunSpanStyle(
    start: Int,
    endInclusive: Int,
    gStart: Int,
    gEnd: Int,
    transform: (OdfSpan) -> OdfSpan) {
    val doc = curText() ?: return
    updateDocument(doc.applyRunSpanStyle(start, endInclusive, gStart, gEnd, transform) ?: return)
}

/** True if every character in the run selection (or caret's whole paragraph) satisfies [predicate]. */
fun OfficeViewModel.runRangeHasFormat(
    start: Int,
    endInclusive: Int,
    gStart: Int,
    gEnd: Int,
    predicate: (OdfSpan) -> Boolean): Boolean =
    curText()?.runRangeHasFormat(start, endInclusive, gStart, gEnd, predicate) ?: false

/** Paragraph index (within the document) at the given run-global caret position. */
fun OfficeViewModel.runParagraphIndexAt(start: Int, endInclusive: Int, gPos: Int): Int =
    curText()?.runParagraphIndexAt(start, endInclusive, gPos) ?: start

/** Applies a paragraph-level mutation to every paragraph touched by the run selection. */
fun OfficeViewModel.mutateRunParagraphs(
    start: Int,
    endInclusive: Int,
    gStart: Int,
    gEnd: Int,
    transform: (OdfParagraph) -> OdfParagraph) {
    val doc = curText() ?: return
    updateDocument(doc.mutateRunParagraphs(start, endInclusive, gStart, gEnd, transform) ?: return)
}

/** Inserts literal text at the caret position within a paragraph run (B17/B18). */
fun OfficeViewModel.insertTextInRun(start: Int, endInclusive: Int, gPos: Int, insert: String) {
    val doc = curText() ?: return
    updateDocument(doc.insertTextInRun(start, endInclusive, gPos, insert) ?: return)
}

/** Clears all character formatting across a run selection (B24). */
fun OfficeViewModel.clearRunFormatting(start: Int, endInclusive: Int, gStart: Int, gEnd: Int) {
    val doc = curText() ?: return
    updateDocument(doc.clearRunFormatting(start, endInclusive, gStart, gEnd) ?: return)
}

/** Promote/demote a list item's nesting level (B13). */
fun OfficeViewModel.changeListLevel(blockIndex: Int, delta: Int) {
    val doc = curText() ?: return
    updateDocument(doc.changeListLevel(blockIndex, delta) ?: return)
}

/** Restart numbering at 1 for a numbered list item (B13). */
fun OfficeViewModel.restartNumbering(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.restartNumbering(blockIndex) ?: return)
}

/** Inserts an image (already-read bytes) after the given block (B14). */
fun OfficeViewModel.insertImage(blockIndex: Int, fileName: String, bytes: ByteArray) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val path = uniqueImagePath(doc.images.keys, fileName)
    val content = doc.content.toMutableList()
    val at = (blockIndex + 1).coerceIn(0, content.size)
    content.add(at, OdfContentBlock.Image(OdfImage(path = path, imageData = bytes)))
    updateDocument(doc.copy(content = content, images = doc.images + (path to bytes)))
}

/** Generates a unique package path for a newly-inserted image so two inserts never collide. (A6) */
internal fun OfficeViewModel.uniqueImagePath(existing: Set<String>, fileName: String): String {
    val safe = fileName.substringAfterLast('/').ifBlank { "image" }
    val base = safe.substringBeforeLast('.', safe)
    val ext = safe.substringAfterLast('.', "")
    var candidate = "Pictures/$safe"
    var n = 1
    while (candidate in existing) {
        candidate = if (ext.isNotEmpty()) "Pictures/${base}_$n.$ext" else "Pictures/${base}_$n"
        n++
    }
    return candidate
}

/** Sets non-destructive crop insets on a text-document image block. (Phase 5) */
fun OfficeViewModel.setImageCrop(blockIndex: Int, left: Float, top: Float, right: Float, bottom: Float) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Image ?: return
    val content = doc.content.toMutableList()
    content[blockIndex] = OdfContentBlock.Image(block.image.copy(
        cropLeftPct = left, cropTopPct = top, cropRightPct = right, cropBottomPct = bottom
    ))
    updateDocument(doc.copy(content = content))
}

/** Inserts a horizontal rule (rendered as a bordered empty paragraph) (B19). */
fun OfficeViewModel.insertHorizontalLine(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.insertHorizontalLine(blockIndex))
}

/**
 * Deletes the block immediately before [runStart] if it's a non-paragraph object (image, page
 * break, chart, table of contents, formula, table…). Lets Backspace at the very start of a run
 * remove the object above it. Returns true if something was deleted.
 */
fun OfficeViewModel.deleteBlockBefore(runStart: Int): Boolean {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return false
    val idx = runStart - 1
    val block = doc.content.getOrNull(idx) ?: return false
    if (block is OdfContentBlock.Paragraph) return false // ordinary text merge is handled by the editor
    val content = doc.content.toMutableList()
    content.removeAt(idx)
    // Drop an image's binary data too so it isn't left orphaned in the package.
    val newDoc = if (block is OdfContentBlock.Image)
        doc.copy(content = content, images = doc.images - block.image.path)
    else doc.copy(content = content)
    updateDocument(newDoc)
    return true
}

/** Generates a Table of Contents from headings and inserts it at [blockIndex] (the cursor). */
private const val HEADING_LEVEL_3 = 3
private const val HEADING_LEVEL_4 = 4
private const val TOC_INDENT_DP = 18f

fun OfficeViewModel.insertTableOfContents(blockIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val headingStyles = setOf(
        ParagraphStyle.HEADING1,
        ParagraphStyle.HEADING2,
        ParagraphStyle.HEADING3,
        ParagraphStyle.HEADING4)
    val entries = mutableListOf<OdfParagraph>()
    for (block in doc.content) {
        val para = (block as? OdfContentBlock.Paragraph)?.paragraph ?: continue
        val text = para.takeIf { it.style in headingStyles }?.spans?.joinToString("") { it.text }?.trim()
        if (!text.isNullOrEmpty()) {
            val level = when (para.style) {
                ParagraphStyle.HEADING1 -> 1
                ParagraphStyle.HEADING2 -> 2
                ParagraphStyle.HEADING3 -> HEADING_LEVEL_3
                else -> HEADING_LEVEL_4
            }
            entries.add(OdfParagraph(listOf(OdfSpan(text = text)), marginLeft = (level - 1) * TOC_INDENT_DP))
        }
    }
    if (entries.isEmpty()) return
    val toc = OdfContentBlock.TableOfContents("Table of Contents", entries)
    val content = doc.content.toMutableList()
    val at = (blockIndex + 1).coerceIn(0, content.size)
    content.add(at, OdfContentBlock.PageBreak)
    content.add(at, toc)
    updateDocument(doc.copy(content = content))
}
