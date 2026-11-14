package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection

/** A styled paragraph: spans plus alignment, margins, list and border properties. */
data class OdfParagraph(
    val spans: List<OdfSpan>,
    val style: ParagraphStyle = ParagraphStyle.BODY,
    val alignment: TextAlign? = null,
    val marginLeft: Float = 0f,
    val marginTop: Float = 0f,
    val marginBottom: Float = 0f,
    val textIndent: Float = 0f,
    val backgroundColor: Long? = null,
    val listLevel: Int = 0,
    val listType: ListType = ListType.BULLET,
    val listItemIndex: Int = 0,
    // Checked state for CHECKBOX list items (loext:checkbox-status). Ignored for other list types.
    val listChecked: Boolean = false,
    val direction: LayoutDirection? = null,
    // Line spacing as a multiple of normal (1.0 = single, 1.5 = 150%); null = unspecified. (A6)
    val lineHeightPercent: Float? = null,
    // Paragraph border color (single uniform border) if any. (A7)
    val borderColor: Long? = null,
    // Per-edge styled borders (raw fo:border values), if any (Priority 4). Preserved on round-trip.
    val borders: OdfBorders? = null,
    // Number format for numbered lists: "1", "a", "A", "i", "I". (F42)
    val listNumberFormat: String = "1",
    // Bullet glyph for bullet lists. (F42)
    val listBulletChar: String = "•",
    // Number prefix/suffix (e.g. "(" and ")" for "(1)"). (F42)
    val listNumberPrefix: String = "",
    val listNumberSuffix: String = ".",
    // Tab stop positions in px (96dpi). (A4)
    val tabStops: List<Float> = emptyList(),
    // Rich tab stops with type (left/right/center/char) and leader char, if any. (Phase 2)
    // When non-empty, takes precedence over [tabStops] on serialize.
    val tabStopDetails: List<OdfTabStop> = emptyList(),
    // Drop cap (style:drop-cap): number of lines the initial spans; 0 = none. (Phase 2)
    val dropCapLines: Int = 0,
    val dropCapLength: Int = 1,
    // Uniform paragraph padding in px@96 (fo:padding), preserved for round-trip. (Phase 6)
    val padding: Float = 0f,
    // Emit fo:break-before="page" on this paragraph's style. (B4)
    val breakBeforePage: Boolean = false,
    // Extended paragraph properties (Round 2 R3).
    val marginRight: Float = 0f,
    val keepWithNext: Boolean = false,
    val keepTogether: Boolean = false,
    val widows: Int? = null,
    val orphans: Int? = null,
    // Computed automatic heading number from text:outline-style, e.g. "1.2." (Round 3).
    val outlineNumber: String? = null
)

/** The named paragraph roles: headings, body, list items and table headers. */
enum class ParagraphStyle { HEADING1, HEADING2, HEADING3, HEADING4, BODY, LIST_ITEM, TABLE_HEADER }

/**
 * A paragraph tab stop (style:tab-stop). [position] is px@96. [type] is one of
 * "left"/"right"/"center"/"char" (default "left"); [leaderChar] is the fill char
 * (e.g. "." for a dotted leader), preserved for round-trip. (Phase 2)
 */
data class OdfTabStop(
    val position: Float,
    val type: String? = null,
    val leaderChar: String? = null
)

/** The list renderings a paragraph can belong to. */
enum class ListType { BULLET, NUMBERED, CHECKBOX }

/** Formats a 1-based list item index using an ODF number-format token ("1","a","A","i","I"). (F42) */
fun formatListNumber(index: Int, format: String): String {
    val n = index.coerceAtLeast(1)
    return when (format) {
        "a" -> toAlpha(n).lowercase()
        "A" -> toAlpha(n)
        "i" -> toRoman(n).lowercase()
        "I" -> toRoman(n)
        else -> n.toString()
    }
}

private fun toAlpha(n: Int): String {
    val sb = StringBuilder()
    var v = n
    while (v > 0) {
        val rem = (v - 1) % 26
        sb.insert(0, ('A' + rem))
        v = (v - 1) / 26
    }
    return sb.toString()
}

private fun toRoman(n: Int): String {
    if (n !in 1..3999) return n.toString()
    val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
    val sb = StringBuilder()
    var v = n
    for (i in values.indices) {
        while (v >= values[i]) { sb.append(symbols[i]); v -= values[i] }
    }
    return sb.toString()
}

/** A run of text sharing one character format, link, field or change marker. */
data class OdfSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val fontSize: Float? = null,
    val fontFamily: String? = null,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val color: Long? = null,
    val backgroundColor: Long? = null,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val href: String? = null,
    val annotation: OdfAnnotation? = null,
    // ODF text field kind ("date","time","page-number",...). null = plain text. Display value in [text].
    val field: String? = null,
    // Cross-reference (Priority 5): refKind is one of "reference-ref","bookmark-ref",
    // "reference-mark","reference-mark-start","reference-mark-end". refName = target/mark name;
    // refFormat = text:reference-format (e.g. "page","chapter","text"). Display value in [text].
    val refName: String? = null,
    val refKind: String? = null,
    val refFormat: String? = null,
    // Track changes (Priority 6): changeKind = "insertion" or "deletion"; changeId references an OdfChange.
    val changeId: String? = null,
    val changeKind: String? = null,
    // Extended character formatting (Round 2 R2).
    val underlineStyle: String? = null,   // solid/double/dotted/dash/wave (null = none unless [underline])
    val underlineColor: Long? = null,
    val letterSpacing: Float? = null,     // pt
    val textTransform: String? = null,    // uppercase/lowercase/capitalize
    val language: String? = null,
    val country: String? = null
)

/** A footnote or endnote: its citation marker and body paragraphs. */
data class OdfFootnote(
    val citation: String,
    val body: List<OdfParagraph>,
    val isEndnote: Boolean = false
)

/** A comment annotation: author, date and body paragraphs. */
data class OdfAnnotation(
    val author: String? = null,
    val date: String? = null,
    val paragraphs: List<OdfParagraph> = emptyList()
)

/** A named bookmark anchored at a content-block index. */
data class OdfBookmark(
    val name: String,
    val contentIndex: Int
)
