package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfCondFormat
import com.vayunmathur.library.ui.odf.OdfDataValidation
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSpan
import org.xmlpull.v1.XmlPullParser

/** XLSX post-processing: merges, hyperlinks, condformat, comments, validation (split from OoxmlXlsx). */
internal object OoxmlXlsxPost {

    fun applyMerges(sheet: OdfSheet, merges: List<String>): OdfSheet {
        if (merges.isEmpty()) return sheet
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for (m in merges) {
            applyMerge(grid, m)
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    /** Ensure the grid covers cell (r, c). */
    private fun ensureMergeCell(grid: MutableList<MutableList<OdfCell>>, r: Int, c: Int) {
        while (grid.size <= r) grid.add(mutableListOf())
        while (grid[r].size <= c) grid[r].add(OdfCell(text = ""))
    }

    /** Apply one merge range. */
    private fun applyMerge(grid: MutableList<MutableList<OdfCell>>, m: String) {
        val (a, b) = m.split(":").let { if (it.size == 2) it[0] to it[1] else it[0] to it[0] }
        val r1 = OoxmlXml.rowIndex(a); val c1 = OoxmlXml.colIndex(a)
        val r2 = OoxmlXml.rowIndex(b); val c2 = OoxmlXml.colIndex(b)
        if (r1 < 0 || c1 < 0) return
        ensureMergeCell(grid, r1, c1)
        grid[r1][c1] = grid[r1][c1].copy(
            spannedColumns = (c2 - c1 + 1).coerceAtLeast(1),
            rowSpan = (r2 - r1 + 1).coerceAtLeast(1))
        for (r in r1..r2) for (c in c1..c2) {
            if (r == r1 && c == c1) continue
            ensureMergeCell(grid, r, c)
            grid[r][c] = grid[r][c].copy(isCovered = true)
        }
    }

    fun applyHyperlinks(
        sheet: OdfSheet,
        links: List<Triple<String,
        String?,
        String?>>,
        rels: Map<String,
        OoxmlPackage.Rel>): OdfSheet {
        if (links.isEmpty()) return sheet
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for ((ref, rId, loc) in links) {
            val first = ref.split(":").first()
            val r = OoxmlXml.rowIndex(first); val c = OoxmlXml.colIndex(first)
            val target = rId?.let { rels[it]?.target } ?: loc?.let { "#$it" }
            if (r in grid.indices && c in grid[r].indices && target != null) {
                grid[r][c] = grid[r][c].copy(hyperlink = target)
            }
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    internal class CfRule(val condition: String, val bg: Long?, val fontColor: Long?)

    fun parseCfRules(parser: XmlPullParser): List<CfRule> {
        val depth = parser.depth
        val rules = mutableListOf<CfRule>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "conditionalFormatting")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "cfRule") {
                parseCfRule(parser)?.let { rules.add(it) }
            }
            e = parser.next()
        }
        return rules
    }

    /** One cfRule (formulas + dxf reference). */
    private fun parseCfRule(parser: XmlPullParser): CfRule? {
        val type = OoxmlXml.attr(parser, "type")
        val op = OoxmlXml.attr(parser, "operator")
        val dxfId = OoxmlXml.attr(parser, "dxfId")?.toIntOrNull()
        val formulas = readCfFormulas(parser)
        if (formulas.isEmpty()) return null
        return when (type) {
            "cellIs" -> {
                val cond = cellIsCondition(op, formulas)
                CfRule(cond, null, null).let { it.copyWithDxf(dxfId) }
            }
            "expression" -> CfRule(formulas[0], null, null).let { it.copyWithDxf(dxfId) }
            else -> null
        }
    }

    /** Formula children of a cfRule. */
    private fun readCfFormulas(parser: XmlPullParser): List<String> {
        val d = parser.depth
        val formulas = mutableListOf<String>()
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == d && parser.name == "cfRule")) {
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG && parser.name == "formula") {
                formulas.add(OoxmlXml.readElementText(parser, "formula"))
            }
            ev = parser.next()
        }
        return formulas
    }

    // dxf colors resolved later against StyleTable; store id via a sentinel condition suffix.
    private fun CfRule.copyWithDxf(dxfId: Int?): CfRule = CfRule(
        if (dxfId != null) "$condition $dxfId" else condition,
        bg,
        fontColor)

    private fun cellIsCondition(op: String?, formulas: List<String>): String = when (op) {
        "greaterThan" -> "value()>${formulas[0]}"
        "lessThan" -> "value()<${formulas[0]}"
        "greaterThanOrEqual" -> "value()>=${formulas[0]}"
        "lessThanOrEqual" -> "value()<=${formulas[0]}"
        "equal" -> "value()=${formulas[0]}"
        "notEqual" -> "value()!=${formulas[0]}"
        "between" ->
            if (formulas.size >= 2) "value()>=${formulas[0]} and value()<=${formulas[1]}" else "value()>=${formulas[0]}"
        else -> "value()=${formulas.getOrElse(0) { "0" }}"
    }

    fun applyCondFormats(
        sheet: OdfSheet,
        cfs: List<Pair<String, List<CfRule>>>,
        styles: OoxmlXlsxStyles.StyleTable,
    ): OdfSheet {
        if (cfs.isEmpty()) return sheet
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for ((sqref, rules) in cfs) {
            val odfRules = rules.map { r ->
                val parts = r.condition.split(' ')
                val cond = parts[0]
                val dxf = parts.getOrNull(1)?.toIntOrNull()?.let { styles.dxfs.getOrNull(it) }
                OdfCondFormat(condition = cond, backgroundColor = dxf?.fill, textColor = dxf?.fontColor)
            }
            for (range in sqref.split(" ")) {
                val (a, b) = range.split(":").let { if (it.size == 2) it[0] to it[1] else it[0] to it[0] }
                val r1 = OoxmlXml.rowIndex(a); val c1 = OoxmlXml.colIndex(a)
                val r2 = OoxmlXml.rowIndex(b); val c2 = OoxmlXml.colIndex(b)
                for (r in r1..r2) for (c in c1..c2) {
                    if (r in grid.indices && c in grid[r].indices) grid[r][c] =
                        grid[r][c].copy(condFormats = grid[r][c].condFormats + odfRules)
                }
            }
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    fun parseDataValidation(parser: XmlPullParser): Pair<OdfDataValidation, List<String>>? {
        val type = OoxmlXml.attr(parser, "type") ?: return null
        val sqref = OoxmlXml.attr(parser, "sqref") ?: return null
        val formulas = readValidationFormulas(parser)
        val name = "val_${sqref.replace(Regex("[^A-Za-z0-9]"), "_")}"
        return OdfDataValidation(name, validationCondition(type, formulas)) to sqref.split(" ")
    }

    /** Formula children of a dataValidation. */
    private fun readValidationFormulas(parser: XmlPullParser): List<String> {
        val depth = parser.depth
        val formulas = mutableListOf<String>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "dataValidation")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && (parser.name == "formula1" || parser.name == "formula2")) {
                formulas.add(OoxmlXml.readElementText(parser, parser.name))
            }
            e = parser.next()
        }
        return formulas
    }

    /** ODF validation condition for a type + formulas. */
    private fun validationCondition(type: String, formulas: List<String>): String = when (type) {
        "list" -> {
            val f = formulas.getOrElse(0) { "" }.trim('"')
            val values = f.split(",").joinToString(";") { "\"${it.trim()}\"" }
            "of:cell-content-is-in-list($values)"
        }
        "whole", "decimal" -> "of:cell-content()>=${formulas.getOrElse(0) { "0" }}"
        "textLength" -> "of:cell-content-text-length()>=${formulas.getOrElse(0) { "0" }}"
        else -> "of:cell-content()"
    }

    fun applyValidationNames(sheet: OdfSheet, list: List<Pair<String, List<String>>>): OdfSheet {
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for ((name, refs) in list) for (range in refs) {
            val (a, b) = range.split(":").let { if (it.size == 2) it[0] to it[1] else it[0] to it[0] }
            val r1 = OoxmlXml.rowIndex(a); val c1 = OoxmlXml.colIndex(a)
            val r2 = OoxmlXml.rowIndex(b); val c2 = OoxmlXml.colIndex(b)
            for (r in r1..r2) for (c in c1..c2) if (r in grid.indices && c in grid[r].indices) grid[r][c] =
                grid[r][c].copy(validationName = name)
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    fun applyComments(
        pkg: OoxmlPackage,
        rels: Map<String,
        OoxmlPackage.Rel>,
        sheet: OdfSheet): OdfSheet {
        val commentsPart = rels.values.firstOrNull { it.type?.endsWith("comments") == true }?.target ?: return sheet
        val xml = pkg.entries[commentsPart] ?: return sheet
        val comments = parseSheetComments(xml)
        if (comments.isEmpty()) return sheet
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for ((ref, ann) in comments) {
            val r = OoxmlXml.rowIndex(ref); val c = OoxmlXml.colIndex(ref)
            if (r in grid.indices && c in grid[r].indices) grid[r][c] = grid[r][c].copy(annotation = ann)
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    private fun parseSheetComments(xml: String): Map<String, OdfAnnotation> {
        val parser = OoxmlXml.newParser(xml)
        val authors = mutableListOf<String>()
        val out = LinkedHashMap<String, OdfAnnotation>()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "author" -> authors.add(OoxmlXml.readElementText(parser, "author"))
                    "comment" -> parseSheetComment(parser, authors)?.let { (ref, ann) -> out[ref] = ann }
                }
            }
            e = parser.next()
        }
        return out
    }

    /** One sheet comment (ref + annotation). */
    private fun parseSheetComment(
        parser: XmlPullParser,
        authors: List<String>,
    ): Pair<String, OdfAnnotation>? {
        val ref = OoxmlXml.attr(parser, "ref") ?: ""
        val aIdx = OoxmlXml.attr(parser, "authorId")?.toIntOrNull()
        val text = readCommentText(parser)
        if (ref.isBlank()) return null
        return ref to OdfAnnotation(
            author = aIdx?.let { authors.getOrNull(it) },
            paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text))))
        )
    }

    /** Text content of a comment element. */
    private fun readCommentText(parser: XmlPullParser): String {
        val depth = parser.depth
        val sb = StringBuilder()
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "comment")) {
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG && parser.name == "t") sb.append(OoxmlXml.readElementText(
                parser,
                "t"))
            ev = parser.next()
        }
        return sb.toString()
    }
}
