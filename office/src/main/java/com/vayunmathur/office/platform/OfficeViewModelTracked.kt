package com.vayunmathur.office.platform

import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfBookmark
import com.vayunmathur.library.ui.odf.OdfChange
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFootnote
import com.vayunmathur.library.ui.odf.OdfMetadata
import com.vayunmathur.library.ui.odf.OdfPageSetup
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan

/**
 * Tracked changes, footnotes, comments, headers/footers, metadata
 * (split from OfficeViewModelTables.kt for file length).
 * Behavior identical, call sites unchanged.
 */

internal fun OfficeViewModel.transformChangeSpans(
    doc: OdfDocument.TextDocument,
    id: String,
    removeSpans: Boolean): OdfDocument.TextDocument {
    fun mapSpans(spans: List<OdfSpan>): List<OdfSpan> {
        if (spans.none { it.changeId == id }) return spans
        val out = ArrayList<OdfSpan>()
        for (s in spans) {
            if (s.changeId == id) {
                if (removeSpans) continue
                out.add(s.copy(changeId = null, changeKind = null))
            } else out.add(s)
        }
        return out.ifEmpty { listOf(OdfSpan(text = "")) }
    }
    val newContent = doc.content.map { block ->
        when (block) {
            is OdfContentBlock.Paragraph ->
                OdfContentBlock.Paragraph(block.paragraph.copy(spans = mapSpans(block.paragraph.spans)))
            is OdfContentBlock.Table -> OdfContentBlock.Table(block.table.copy(rows = block.table.rows.map { r ->
                r.copy(cells = r.cells.map { c ->
                    c.copy(paragraphs = c.paragraphs.map { p -> p.copy(spans = mapSpans(p.spans)) })
                })
            }))
            is OdfContentBlock.TableOfContents -> OdfContentBlock.TableOfContents(
                block.title,
                block.entries.map { it.copy(spans = mapSpans(it.spans)) })
            else -> block
        }
    }
    return doc.copy(content = newContent, changes = doc.changes.filterNot { it.id == id })
}

/** Accept a tracked change: insertions stay, deletions are applied (text removed). */
fun OfficeViewModel.acceptChange(id: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val type = doc.changes.find { it.id == id }?.type ?: return
    updateDocument(transformChangeSpans(doc, id, removeSpans = type == "deletion"))
}

/** Reject a tracked change: insertions are removed, deletions are reverted (text kept). */
fun OfficeViewModel.rejectChange(id: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val type = doc.changes.find { it.id == id }?.type ?: return
    updateDocument(transformChangeSpans(doc, id, removeSpans = type != "deletion"))
}

fun OfficeViewModel.acceptAllChanges() {
    var doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    for (id in doc.changes.map { it.id }) {
        val type = doc.changes.find { it.id == id }?.type ?: continue
        doc = transformChangeSpans(doc, id, removeSpans = type == "deletion")
    }
    updateDocument(doc)
}

fun OfficeViewModel.rejectAllChanges() {
    var doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    for (id in doc.changes.map { it.id }) {
        val type = doc.changes.find { it.id == id }?.type ?: continue
        doc = transformChangeSpans(doc, id, removeSpans = type != "deletion")
    }
    updateDocument(doc)
}

/** Updates page geometry (size/margins/orientation), persisted to styles.xml on save. (Priority 7) */
fun OfficeViewModel.setPageSetup(setup: OdfPageSetup) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    updateDocument(doc.copy(pageSetup = setup))
}

/** Inserts a footnote with a citation marker at the caret (B15). */
fun OfficeViewModel.insertFootnote(start: Int, endInclusive: Int, gPos: Int, body: String, isEndnote: Boolean = false) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val citation = (doc.footnotes.size + 1).toString()
    val footnotes = doc.footnotes + OdfFootnote(citation, listOf(OdfParagraph(listOf(OdfSpan(text = body)))), isEndnote)
    // Insert the citation marker text at the caret, then attach the footnote.
    val paras = runParas(start, endInclusive)
    if (paras != null) {
        val full = paras.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
        val pos = gPos.coerceIn(0, full.length)
        val newText = full.substring(0, pos) + "[$citation]" + full.substring(pos)
        updateParagraphRun(start, endInclusive, newText)
    }
    val current = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    updateDocument(current.copy(footnotes = footnotes))
}

/** Removes the annotation span at [spanIndex] within paragraph [blockIndex] (resolve comment). (C3) */
fun OfficeViewModel.resolveComment(blockIndex: Int, spanIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
    val spans = block.paragraph.spans.toMutableList()
    val span = spans.getOrNull(spanIndex) ?: return
    if (span.annotation == null) return
    spans.removeAt(spanIndex)
    if (spans.isEmpty()) spans.add(OdfSpan(text = ""))
    val content = doc.content.toMutableList()
    content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(spans = spans))
    updateDocument(doc.copy(content = content))
}

/** Appends a comment/annotation marker to a paragraph (B16). */
fun OfficeViewModel.insertComment(blockIndex: Int, author: String, text: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val content = doc.content.toMutableList()
    val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
    val annotation = OdfAnnotation(
        author = author.ifBlank { null },
        date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()),
        paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = text))))
    )
    val newSpans = block.paragraph.spans + OdfSpan(text = " \uD83D\uDCDD ", annotation = annotation)
    content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(spans = newSpans))
    updateDocument(doc.copy(content = content))
}

/** Sets header/footer text (in-session edit; B20). */
fun OfficeViewModel.setHeaderText(text: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val paras = if (text.isBlank()) emptyList() else text.split("\n").map { OdfParagraph(listOf(OdfSpan(text = it))) }
    updateDocument(doc.copy(headerParagraphs = paras))
}

fun OfficeViewModel.setFooterText(text: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val paras = if (text.isBlank()) emptyList() else text.split("\n").map { OdfParagraph(listOf(OdfSpan(text = it))) }
    updateDocument(doc.copy(footerParagraphs = paras))
}

// --- Text-document table editing ---
