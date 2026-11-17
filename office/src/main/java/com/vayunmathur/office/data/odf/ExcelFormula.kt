package com.vayunmathur.office.data.odf

/**
 * Best-effort translator between Excel A1-style formulas and ODF OpenFormula strings
 * ("of:=…") (Phases C2/X4). Handles cell/range references (incl. sheet-qualified and absolute),
 * whole-column/whole-row references (`A:A`, `1:1`), converts the ',' argument separator
 * to ';', and preserves string literals. Function names are passed through unchanged —
 * most common names (SUM/IF/AVERAGE/…) are identical in both dialects.
 *
 * Excel limits: [MAX_ROWS] x [MAX_COLS] ([MAX_COL_LETTERS]). Whole-column/row references
 * are expanded to explicit full-span ranges (the same form LibreOffice Calc writes),
 * e.g. `A:A` -> `[.A1:.A1048576]`.
 */
internal object ExcelFormula {

    const val MAX_ROWS = 1048576
    const val MAX_COLS = 16384
    val MAX_COL_LETTERS: String = indexToCol(MAX_COLS - 1)

    private val REF = Regex(
        "(?<![A-Za-z0-9_.$])" +
            "(?:('[^']+'|[A-Za-z_][A-Za-z0-9_.]*)!)?" +
            "(\\$?[A-Za-z]{1,3}\\$?[0-9]+(?::\\$?[A-Za-z]{1,3}\\$?[0-9]+)?)" +
            "(?![A-Za-z0-9_(])"
    )

    // Whole-column (`A:A`, `$B:$D`, `Sheet1!A:C`) and whole-row (`1:3`) references.
    // Must run before [REF]: REF cannot match these (it requires row digits on both sides).
    private val WHOLE_COL = Regex(
        "(?<![A-Za-z0-9_.$'])" +
            "(?:('[^']+'|[A-Za-z_][A-Za-z0-9_.]*)!)?" +
            "(\\$?[A-Za-z]{1,3}):(\\$?[A-Za-z]{1,3})" +
            "(?![A-Za-z0-9_(])"
    )
    private val WHOLE_ROW = Regex(
        "(?<![A-Za-z0-9_.$'])" +
            "(?:('[^']+'|[A-Za-z_][A-Za-z0-9_.]*)!)?" +
            "(\\$?[0-9]+):(\\$?[0-9]+)" +
            "(?![A-Za-z0-9_(])"
    )

    // A relative/absolute A1 cell reference (col letters + row), used by [shift].
    // The trailing `!` exclusion keeps sheet qualifiers (`S2!A1`) from being shifted
    // as if the sheet name were a cell.
    private val CELL = Regex(
        "(?<![A-Za-z0-9_$])" +
            "(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]+)" +
            "(?![A-Za-z0-9_(!])"
    )
    private const val REF_SHEET = 1
    private const val REF_COL = 2
    private const val REF_COL_ABS = 3
    private const val REF_ROW = 4
    private const val ALPHA_BASE = 26

    /**
     * Shifts the relative references in an Excel formula body by [dRow]/[dCol] (used to re-base a
     * shared formula from its master cell to a dependent cell). Absolute ($) parts and string
     * literals are left untouched.
     */
    fun shift(excel: String, dRow: Int, dCol: Int): String {
        if (dRow == 0 && dCol == 0) return excel
        val sb = StringBuilder()
        var i = 0
        while (i < excel.length) {
            val c = excel[i]
            if (c == '"') {
                var j = i + 1
                while (j < excel.length && !isStringEnd(excel, j)) {
                    j = if (isEscapedQuote(excel, j)) j + 2 else j + 1
                }
                val end = if (j < excel.length) j else excel.length - 1
                sb.append(excel, i, minOf(end + 1, excel.length)); i = end + 1
            } else {
                val nextQuote = excel.indexOf('"', i).let { if (it < 0) excel.length else it }
                sb.append(shiftSegment(excel.substring(i, nextQuote), dRow, dCol))
                i = nextQuote
            }
        }
        return sb.toString()
    }

    private fun shiftSegment(seg: String, dRow: Int, dCol: Int): String = CELL.replace(seg) { m ->
        val colAbs = m.groupValues[REF_SHEET] == "$"
        val rowAbs = m.groupValues[REF_COL_ABS] == "$"
        val col = if (colAbs) m.groupValues[REF_COL] else indexToCol(
            (colToIndex(m.groupValues[REF_COL]) + dCol).coerceAtLeast(0))
        val row = if (rowAbs) m.groupValues[REF_ROW].toInt() else {
            (m.groupValues[REF_ROW].toInt() + dRow).coerceAtLeast(1)
        }
        "${m.groupValues[REF_SHEET]}$col${m.groupValues[REF_COL_ABS]}$row"
    }

    private fun colToIndex(letters: String): Int {
        var n = 0
        for (c in letters) n = n * ALPHA_BASE + (c.uppercaseChar() - 'A' + 1)
        return n - 1
    }

    private fun indexToCol(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % ALPHA_BASE
            sb.insert(0, ('A' + rem))
            n = (n - 1) / ALPHA_BASE
        }
        return sb.toString()
    }

    /** True at an unescaped closing quote (not part of a "" escape). */
    private fun isStringEnd(s: String, j: Int): Boolean = s[j] == '"' && !isEscapedQuote(s, j)

    /** True at the first quote of a "" escaped pair. */
    private fun isEscapedQuote(s: String, j: Int): Boolean =
        s[j] == '"' && j + 1 < s.length && s[j + 1] == '"'

    /** Returns an "of:=…" formula for the given Excel formula body (with or without a leading '='). */
    fun toOdf(excel: String): String {
        val body = excel.trim().removePrefix("=")
        val sb = StringBuilder("of:=")
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '"') {
                // Scan to the closing quote, treating "" as an escaped quote inside the literal.
                var j = i + 1
                while (j < body.length && !isStringEnd(body, j)) {
                    j = if (isEscapedQuote(body, j)) j + 2 else j + 1
                }
                val end = if (j < body.length) j else body.length - 1
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
        val wholeCols = WHOLE_COL.replace(seg) { m ->
            val sheet = m.groupValues[1]
            wrapWholeCol(sheet, m.groupValues[2], m.groupValues[3])
        }
        val wholeRows = WHOLE_ROW.replace(wholeCols) { m ->
            val sheet = m.groupValues[1]
            wrapWholeRow(sheet, m.groupValues[2], m.groupValues[3])
        }
        val refWrapped = REF.replace(wholeRows) { m ->
            val sheet = m.groupValues[1]
            val ref = m.groupValues[2]
            wrap(sheet, ref)
        }
        return refWrapped.replace(',', ';')
    }

    /** `A:C` -> `[.$A$1:.$C$1048576]`-style full-span range (absolute parts preserved). */
    private fun wrapWholeCol(sheet: String, a: String, b: String): String {
        val (ca, absA) = splitAbs(a)
        val (cb, absB) = splitAbs(b)
        val colA = (if (absA) "$" else "") + ca
        val colB = (if (absB) "$" else "") + cb
        val pre = prefixSheet(sheet)
        return "[$pre$colA" + "1:.$colB$MAX_ROWS]"
    }

    /** `2:5` -> `[.A2:.XFD5]`. */
    private fun wrapWholeRow(sheet: String, a: String, b: String): String {
        val (ra, absA) = splitAbs(a)
        val (rb, absB) = splitAbs(b)
        val pre = prefixSheet(sheet)
        val r1 = "$pre${if (absA) "$" else ""}A${ra.trimStart('$')}"
        val r2 = ".${if (absB) "$" else ""}$MAX_COL_LETTERS${rb.trimStart('$')}"
        return "[$r1:$r2]"
    }

    private fun splitAbs(s: String): Pair<String, Boolean> =
        if (s.startsWith("$")) s.substring(1) to true else s to false

    private val ODF_REF = Regex("\\[([^\\]]*)]")

    /** Returns an Excel-style formula ("=…") for the given OpenFormula ("of:=…"). */
    fun fromOdf(of: String): String {
        var body = of.trim()
        if (body.startsWith("of:", ignoreCase = true)) body = body.substring(OF_PREFIX_LENGTH)
        body = body.trim().removePrefix("=")
        val sb = StringBuilder("=")
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '"') {
                // Copy string literals verbatim ("" is an escaped quote).
                var j = i + 1
                while (j < body.length && !isStringEnd(body, j)) {
                    j = if (isEscapedQuote(body, j)) j + 2 else j + 1
                }
                val end = if (j < body.length) j else body.length - 1
                sb.append(body, i, minOf(end + 1, body.length))
                i = end + 1
            } else {
                val nextQuote = body.indexOf('"', i).let { if (it < 0) body.length else it }
                sb.append(fromOdfSegment(body.substring(i, nextQuote)))
                i = nextQuote
            }
        }
        return sb.toString()
    }

    private fun fromOdfSegment(seg: String): String {
        val refConverted = ODF_REF.replace(seg) { m -> convertOdfRef(m.groupValues[1]) }
        return refConverted.replace(';', ',')
    }

    /** Converts one OpenFormula bracket body ("$Sheet.A1", ".A1", "$A$1") back to Excel A1. */
    private fun convertOdfRef(inner: String): String {
        val parts = inner.split(":")
        if (parts.size == 2) {
            // Full-span ranges (from whole-column/row refs) collapse back to `A:A` / `2:5`.
            wholeColOf(parts)?.let { return it }
            wholeRowOf(parts)?.let { return it }
        }
        return parts.joinToString(":") { convertOdfRefPart(it) }
    }

    private fun wholeColOf(parts: List<String>): String? {
        val a = splitEndpoint(parts[0]) ?: return null
        val b = splitEndpoint(parts[1]) ?: return null
        if (a.row != "1" || b.row != MAX_ROWS.toString()) return null
        if (a.rowAbs || b.rowAbs) return null
        if (a.sheetNorm != b.sheetNorm) return null
        return "${a.sheetPrefix}${a.colAbs}${a.col}:${b.col}"
    }

    private fun wholeRowOf(parts: List<String>): String? {
        val a = splitEndpoint(parts[0]) ?: return null
        val b = splitEndpoint(parts[1]) ?: return null
        if (a.col.uppercase() != "A" || b.col.uppercase() != MAX_COL_LETTERS) return null
        if (a.colAbs.isNotEmpty() || b.colAbs.isNotEmpty()) return null
        if (a.sheetNorm != b.sheetNorm) return null
        return if (a.sheet.isEmpty()) "${a.row}:${b.row}" else "${a.sheetPrefix}${a.row}:${b.row}"
    }

    private data class Endpoint(
        val sheet: String, val sheetPrefix: String, val sheetNorm: String,
        val col: String, val colAbs: String, val row: String, val rowAbs: Boolean,
    )

    private fun splitEndpoint(part: String): Endpoint? {
        val p = part.trim().removePrefix("$")
        val sheet: String
        val sheetPrefix: String
        val cell: String
        if (p.startsWith(".")) {
            sheet = ""; sheetPrefix = ""; cell = p.substring(1)
        } else if (p.contains(".")) {
            sheet = p.substringBefore(".").trim('\'')
            cell = p.substringAfter(".")
            val q = if (sheet.all { it.isLetterOrDigit() || it == '_' }) sheet else "'$sheet'"
            sheetPrefix = "$q!"
        } else return null
        val m = Regex("^(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]+)$").matchEntire(cell.trim()) ?: return null
        return Endpoint(
            sheet, sheetPrefix, sheet.lowercase(),
            m.groupValues[GROUP_COLUMN], m.groupValues[GROUP_COLUMN_DOLLAR], m.groupValues[GROUP_ROW],
            m.groupValues[GROUP_ROW_DOLLAR] == "$")
    }

    private const val OF_PREFIX_LENGTH = 3
    private const val GROUP_COLUMN_DOLLAR = 1
    private const val GROUP_COLUMN = 2
    private const val GROUP_ROW_DOLLAR = 3
    private const val GROUP_ROW = 4

    private val CELL_TOKEN = Regex("^(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]+)$")

    private fun convertOdfRefPart(part: String): String {
        val p = part.trim()
        // Same-sheet refs: ".A1" / ".$A$1".
        if (p.startsWith(".")) return p.substring(1)
        // Dotless absolute from other producers: "[$A$1]" stays "$A$1".
        if (!p.contains(".") && CELL_TOKEN.matches(p)) return p
        // Sheet-qualified: "$Sheet.A1" / "$'My Sheet'.$A$1".
        var q = p
        if (q.startsWith("$")) q = q.substring(1)
        val dot = q.indexOf('.')
        if (dot < 0) return q // Not a ref after all; pass through.
        val sheet = q.substring(0, dot).trim('\'')
        val cell = q.substring(dot + 1)
        val s = if (sheet.all { it.isLetterOrDigit() || it == '_' }) sheet else "'$sheet'"
        return "$s!$cell"
    }

    private fun wrap(sheet: String, ref: String): String {
        return if (ref.contains(':')) {
            val (a, b) = ref.split(':', limit = 2)
            "[${prefixSheet(sheet)}$a:.$b]"
        } else {
            "[${prefixSheet(sheet)}$ref]"
        }
    }

    private fun prefixSheet(sheet: String): String {
        if (sheet.isEmpty()) return "."
        val name = sheet.trim('\'')
        // OpenFormula requires quoting sheet names with spaces/specials: [$'My Sheet'.A1].
        val needsQuote = sheet.startsWith("'") || !name.all { it.isLetterOrDigit() || it == '_' }
        return "\$" + (if (needsQuote) "'$name'" else name) + "."
    }
}
