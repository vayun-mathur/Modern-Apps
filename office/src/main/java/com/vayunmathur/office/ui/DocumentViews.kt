package com.vayunmathur.office.ui

import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.ParagraphStyle

/** Immutable value describing heading item; carries text, level, contentIndex. */
data class HeadingItem(val text: String, val level: Int, val contentIndex: Int)

fun extractHeadings(doc: OdfDocument.TextDocument): List<HeadingItem> {
    val headings = mutableListOf<HeadingItem>()
    doc.content.forEachIndexed { index, block ->
        if (block is OdfContentBlock.Paragraph) {
            val style = block.paragraph.style
            if (style == ParagraphStyle.HEADING1 || style == ParagraphStyle.HEADING2 ||
                style == ParagraphStyle.HEADING3 || style == ParagraphStyle.HEADING4
            ) {
                val text = block.paragraph.spans.joinToString("") { it.text }
                val level = when (style) {
                    ParagraphStyle.HEADING1 -> 1; ParagraphStyle.HEADING2 -> 2
                    ParagraphStyle.HEADING3 -> 3; else -> 4
                }
                headings.add(HeadingItem(text, level, index))
            }
        }
    }
    return headings
}

fun countWords(doc: OdfDocument.TextDocument): Int {
    var count = 0
    for (block in doc.content) {
        if (block is OdfContentBlock.Paragraph) {
            for (span in block.paragraph.spans) {
                count += span.text.split(Regex("\\s+")).count { it.isNotEmpty() }
            }
        }
    }
    return count
}

fun countChars(doc: OdfDocument.TextDocument): Int {
    var count = 0
    for (block in doc.content) {
        if (block is OdfContentBlock.Paragraph) {
            for (span in block.paragraph.spans) {
                count += span.text.length
            }
        }
    }
    return count
}

fun readingTimeMinutes(doc: OdfDocument.TextDocument): Int = maxOf(1, (countWords(doc) + 199) / 200)

// Note: evalCondFormat + paragraphBaseStyle + ParagraphView + linkOrPlain +
// AnnotationPopup live in ParagraphViews.kt; image helpers + OdfImageView live in
// OdfImageViews.kt; chartPalette + formatAxis + OdfChartView live in OdfChartViews.kt;
// MathView lives in MathViews.kt; SpreadsheetValues +
// rememberNativeSpreadsheetValues live in SpreadsheetViews.kt; FloatingElementLayer
// + helpers live in FloatingElementViews.kt; PresentationView + DrawingView live in
// PresentationViews.kt.

