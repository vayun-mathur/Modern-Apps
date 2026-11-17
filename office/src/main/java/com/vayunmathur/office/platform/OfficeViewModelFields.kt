package com.vayunmathur.office.platform

import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfSpan

/**
 * ODF text-field operations (split from OfficeViewModelText.kt for file length).
 * Behavior identical, call sites unchanged.
 */

/** Inserts a real ODF text field (date/time/page-number/...) at the caret. (Priority 2) */
fun OfficeViewModel.insertFieldInRun(start: Int, endInclusive: Int, gPos: Int, kind: String, value: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val paras = runParas(start, endInclusive) ?: return
    val lens = paraLens(paras)
    val (pi, off) = runLocate(lens, gPos)
    val para = paras[pi]
    val chars = spansToChars(para.spans)
    val template = OdfSpan(text = "", field = kind)
    val fieldChars = value.map { template.copy(text = it.toString()) }
    val at = off.coerceIn(0, chars.size)
    chars.addAll(at, fieldChars)
    val newContent = doc.content.toMutableList()
    newContent[start + pi] = OdfContentBlock.Paragraph(para.copy(spans = charsToSpans(chars)))
    updateDocument(doc.copy(content = newContent))
}

/** Computes the current display value for a newly-inserted field. (Priority 2) */
fun OfficeViewModel.fieldDisplayValue(kind: String): String {
    dateTimeFieldValue(kind)?.let { return it }
    docMetaFieldValue(kind)?.let { return it }
    return staticFieldValue(kind)
}

/** Current date/time formatted for a field kind, or null. */
private fun dateTimeFieldValue(kind: String): String? {
    val locale = java.util.Locale.getDefault()
    val now = java.util.Date()
    return when (kind) {
        "date" -> java.text.SimpleDateFormat("yyyy-MM-dd", locale).format(now)
        "time" -> java.text.SimpleDateFormat("HH:mm", locale).format(now)
        else -> null
    }
}

/** Document/metadata-backed field value, or null. */
private fun OfficeViewModel.docMetaFieldValue(kind: String): String? {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document
    val meta = doc?.metadata
    return when (kind) {
        "file-name" -> doc?.title ?: "Untitled"
        "author-name" -> meta?.author ?: meta?.creator ?: ""
        "title" -> meta?.title ?: doc?.title ?: ""
        "subject" -> meta?.subject ?: ""
        "description" -> meta?.description ?: ""
        else -> null
    }
}

/** Static field value (page counts are resolved at render). */
private fun staticFieldValue(kind: String): String = when (kind) {
    "page-number" -> "1"
    "page-count" -> "1"
    else -> ""
}
