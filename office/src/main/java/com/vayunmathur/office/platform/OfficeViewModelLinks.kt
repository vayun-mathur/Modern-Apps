package com.vayunmathur.office.platform

import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.applyRunSpanStyle
import com.vayunmathur.library.ui.odf.setLinkInRun

/**
 * Link operations on paragraph runs (split from OfficeViewModelText.kt for file length).
 * Behavior identical, call sites unchanged.
 */

/** Extent and target of a link span within a run (mirrors library's internal OdfLinkSpan). */
data class OdfLinkInfo(val gStart: Int, val gEnd: Int, val text: String, val url: String)

/** Finds the link covering the caret in a run, or null. */
fun OfficeViewModel.linkAt(start: Int, endInclusive: Int, gPos: Int): OdfLinkInfo? {
    val doc = curText() ?: return null
    val paras = runParas(start, endInclusive) ?: return null
    val lens = paraLens(paras)
    val (pi, off) = runLocate(lens, gPos)
    val chars = spansToChars(paras[pi].spans)
    val href = chars.getOrNull(off)?.href ?: chars.getOrNull(off - 1)?.href ?: return null
    var l = off.coerceIn(0, chars.size)
    while (l > 0 && chars[l - 1].href == href) l--
    var r = off.coerceIn(0, chars.size)
    while (r < chars.size && chars[r].href == href) r++
    val text = chars.subList(l, r).joinToString("") { it.text }
    val base = paragraphStartGlobal(lens, pi)
    return OdfLinkInfo(base + l, base + r, text, href)
}

/** Global char offset of a paragraph start. */
internal fun OfficeViewModel.paragraphStartGlobal(lens: List<Int>, pi: Int): Int {
    var base = 0
    for (i in 0 until pi) base += lens[i] + 1
    return base
}

/** Plain text of the current run selection (used to pre-fill the link dialog). */
fun OfficeViewModel.runSelectedText(start: Int, endInclusive: Int, gStart: Int, gEnd: Int): String {
    val doc = curText() ?: return ""
    val full = (start..endInclusive)
        .mapNotNull { (doc.content.getOrNull(it) as? OdfContentBlock.Paragraph)?.paragraph }
        .joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    val s = minOf(gStart, gEnd).coerceIn(0, full.length)
    val e = maxOf(gStart, gEnd).coerceIn(s, full.length)
    return full.substring(s, e)
}

/** Replaces a run range with a single link span. */
fun OfficeViewModel.setLink(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, text: String, url: String) {
    val doc = curText() ?: return
    updateDocument(doc.setLinkInRun(start, endInclusive, gStart, gEnd, text, url) ?: return)
}

/** Removes the link over a run range, keeping the text. */
fun OfficeViewModel.removeLinkInRun(start: Int, endInclusive: Int, gStart: Int, gEnd: Int) {
    val doc = curText() ?: return
    updateDocument(doc.applyRunSpanStyle(start, endInclusive, gStart, gEnd) {
        it.copy(href = null, underline = false, color = null)
    } ?: return)
}
