package com.vayunmathur.office.util

/**
 * Universal character-level projection of a **flat ODF XML** document: text content between tags
 * becomes individual character cells (char-merged by [DocumentCrdt]), while tags stay whole cells and
 * `office:binary-data` content (base64 images) stays a single opaque cell (never char-split).
 *
 * This lets *any* document — including ones with images, tables, shapes, notes — merge concurrent
 * edits to the same text region at character granularity, while non-text structure merges at element
 * granularity. Rebuild simply concatenates the cells back into the original XML, so a clean (no
 * concurrent structural conflict) merge always reproduces valid XML.
 */
object FlatXmlCharCodec {
    private const val SEP = '\u0002'

    fun toCells(xml: String): List<String> {
        val cells = ArrayList<String>(xml.length)
        val n = xml.length
        var i = 0
        var inBinary = false
        while (i < n) {
            if (xml[i] == '<') {
                // Scan to the closing '>', skipping any '>' inside quoted attribute values.
                var j = i + 1
                var quote = '\u0000'
                while (j < n) {
                    val c = xml[j]
                    when {
                        quote != '\u0000' -> if (c == quote) quote = '\u0000'
                        c == '"' || c == '\'' -> quote = c
                        c == '>' -> break
                    }
                    j++
                }
                val tag = xml.substring(i, minOf(j + 1, n))
                cells.add("t$SEP$tag")
                when {
                    tag.startsWith("<office:binary-data") -> inBinary = !tag.endsWith("/>")
                    tag.startsWith("</office:binary-data") -> inBinary = false
                }
                i = j + 1
            } else {
                val end = xml.indexOf('<', i).let { if (it < 0) n else it }
                val text = xml.substring(i, end)
                if (inBinary) cells.add("b$SEP$text") // base64 blob stays a single opaque cell
                else for (c in text) cells.add("c$SEP$c")
                i = end
            }
        }
        return cells
    }

    fun fromCells(cells: List<String>): String {
        val sb = StringBuilder()
        for (cell in cells) {
            val sep = cell.indexOf(SEP)
            if (sep >= 0) sb.append(cell.substring(sep + 1))
        }
        return sb.toString()
    }
}
