package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfGradient
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import org.xmlpull.v1.XmlPullParser

private const val ODF_ANGLE_DIVISOR = 10f

// --- CSV parsing ---

fun OdfParser.parseCsv(text: String, fileName: String, delimiter: Char = ','): OdfDocument.Spreadsheet {
    val acc = CsvAcc(delimiter = delimiter)
    // Single pass over the whole text so a quoted field can span commas AND newlines.
    // RFC-4180 style: "" inside a quoted field is a literal quote; \r\n / \r / \n end a record.
    var i = 0
    while (i < text.length) {
        i = stepCsvChar(text, i, acc)
    }
    // Flush the final record when the file doesn't end with a newline.
    if (acc.sb.isNotEmpty() || acc.fields.isNotEmpty()) acc.endRecord()
    return OdfDocument.Spreadsheet(fileName, listOf(OdfSheet("Sheet 1", acc.rows)))
}

/** CSV parse state. */
private class CsvAcc(
    val rows: MutableList<OdfRow> = mutableListOf(),
    val fields: MutableList<String> = mutableListOf(),
    val sb: StringBuilder = StringBuilder(),
    var inQuotes: Boolean = false,
    val delimiter: Char = ',',
) {
    fun endRecord() {
        fields.add(sb.toString()); sb.clear()
        // Skip truly blank lines (mirrors the old per-line isBlank() skip) so trailing
        // newlines and blank separators don't create empty rows. A row like ",," has
        // multiple (empty) fields and is kept, matching the previous behavior.
        if (!(fields.size == 1 && fields[0].isBlank())) {
            rows.add(OdfRow(fields.map { OdfCell(text = it) }))
        }
        fields.clear()
    }
}

private fun stepCsvChar(text: String, index: Int, acc: CsvAcc): Int {
    var i = index
    val c = text[i]
    when {
        acc.inQuotes -> i = stepCsvQuoted(text, i, c, acc)
        // A quote only opens a quoted field at the start of a field; a quote in
        // the middle of an unquoted field (e.g. 5" nail) is literal text.
        c == '"' && acc.sb.isEmpty() -> acc.inQuotes = true
        c == '"' -> acc.sb.append(c)
        c == acc.delimiter -> { acc.fields.add(acc.sb.toString()); acc.sb.clear() }
        c == '\r' -> { acc.endRecord(); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
        c == '\n' -> acc.endRecord()
        else -> acc.sb.append(c)
    }
    return i + 1
}

private fun stepCsvQuoted(text: String, index: Int, c: Char, acc: CsvAcc): Int {
    var i = index
    when {
        c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { acc.sb.append('"'); i++ }
        c == '"' -> acc.inQuotes = false
        else -> acc.sb.append(c) // newlines/commas inside quotes are literal content
    }
    return i
}

/** Parses draw:gradient definitions from a styles/content XML. */
internal fun OdfParser.parseGradients(xml: String): Map<String, OdfGradient> {
    val map = mutableMapOf<String, OdfGradient>()
    val parser = newParser(xml)
    var e = parser.eventType
    while (e != XmlPullParser.END_DOCUMENT) {
        if (e == XmlPullParser.START_TAG && parser.name == "gradient") {
            val nm = getAttr(parser, "name")
            val start = getAttr(parser, "start-color")?.let { parseColor(it) }
            val end = getAttr(parser, "end-color")?.let { parseColor(it) }
            if (nm != null && start != null && end != null) {
                val angle = getAttr(parser, "angle")?.let { a ->
                    // ODF gradient angle is in 1/10 degree, or with "deg" suffix in newer files.
                    a.removeSuffix("deg").toFloatOrNull()?.let { if (a.endsWith("deg")) it else it / ODF_ANGLE_DIVISOR }
                } ?: 0f
                map[nm] = OdfGradient(start, end, angle, getAttr(parser, "style") ?: "linear")
            }
        }
        e = parser.next()
    }
    return map
}
