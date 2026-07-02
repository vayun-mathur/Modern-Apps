package com.vayunmathur.office.odf

/**
 * Best-effort translator from an Excel A1-style formula to an ODF OpenFormula string
 * ("of:=…") (Phases C2/X4). Handles cell/range references (incl. sheet-qualified and absolute),
 * converts the ',' argument separator to ';', and preserves string literals. Function names are
 * passed through unchanged — most common names (SUM/IF/AVERAGE/…) are identical in both dialects.
 */
internal object ExcelFormula {

    private val REF = Regex(
        "(?<![A-Za-z0-9_.$])" +
            "(?:('[^']+'|[A-Za-z_][A-Za-z0-9_.]*)!)?" +
            "(\\$?[A-Za-z]{1,3}\\$?[0-9]+(?::\\$?[A-Za-z]{1,3}\\$?[0-9]+)?)" +
            "(?![A-Za-z0-9_(])"
    )

    /** Returns an "of:=…" formula for the given Excel formula body (with or without a leading '='). */
    fun toOdf(excel: String): String {
        val body = excel.trim().removePrefix("=")
        val sb = StringBuilder("of:=")
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '"') {
                val end = body.indexOf('"', i + 1).let { if (it < 0) body.length else it }
                sb.append(body, i, minOf(end + 1, body.length))
                i = end + 1
            } else {
                // find next string-literal start; convert the segment between
                val nextQuote = body.indexOf('"', i).let { if (it < 0) body.length else it }
                sb.append(convertSegment(body.substring(i, nextQuote)))
                i = nextQuote
            }
        }
        return sb.toString()
    }

    private fun convertSegment(seg: String): String {
        val refWrapped = REF.replace(seg) { m ->
            val sheet = m.groupValues[1]
            val ref = m.groupValues[2]
            wrap(sheet, ref)
        }
        return refWrapped.replace(',', ';')
    }

    private fun wrap(sheet: String, ref: String): String {
        return if (ref.contains(':')) {
            val (a, b) = ref.split(':', limit = 2)
            "[${prefixSheet(sheet)}$a:.$b]"
        } else {
            "[${prefixSheet(sheet)}$ref]"
        }
    }

    private fun prefixSheet(sheet: String): String =
        if (sheet.isEmpty()) "." else "\$${sheet.trim('\'')}."
}
