package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.OdfBorders
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableColumn
import com.vayunmathur.library.ui.odf.OdfTableRow
import org.xmlpull.v1.XmlPullParser

/** DOCX table parser (split from OoxmlDocx for file length; behavior identical). */
internal object OoxmlDocxTables {

    private class TableAcc(
        val grid: MutableList<MutableList<OdfTableCell>> = mutableListOf(),
        val columns: MutableList<OdfTableColumn> = mutableListOf(),
        var headerRows: Int = 0,
        var tblBorders: OdfBorders? = null,
        val vAnchors: HashMap<Int, Pair<Int, Int>> = HashMap(),
    )

    fun parseTable(parser: XmlPullParser, ctx: OoxmlDocx.DocxCtx): OdfTable {
        val depth = parser.depth
        val acc = TableAcc()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tbl")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyTableTag(parser, ctx, acc)
            e = parser.next()
        }
        return buildTable(acc)
    }

    private fun applyTableTag(parser: XmlPullParser, ctx: OoxmlDocx.DocxCtx, acc: TableAcc) {
        when (parser.name) {
            "gridCol" -> applyGridColTag(parser, acc)
            "tblBorders" -> acc.tblBorders = OoxmlDocxStyles.parseBorders(parser, "tblBorders")
            "tr" -> if (parseRow(parser, ctx, acc.grid, acc.vAnchors)) acc.headerRows = acc.grid.size
        }
    }

    private fun applyGridColTag(parser: XmlPullParser, acc: TableAcc) {
        OoxmlXml.attr(
            parser,
            "w")?.toIntOrNull()?.let { acc.columns.add(OdfTableColumn(OoxmlUnits.twipsToPx(it))) }
    }

    private fun buildTable(acc: TableAcc): OdfTable {
        val borders = acc.tblBorders
        val rows = acc.grid.map { cells ->
            val decorated = if (borders != null && !borders.isEmpty()) cells.map { c ->
                if (c.borders == null && !c.isCovered) c.copy(
                    borders = borders,
                    borderColor = OdfBorders.renderColor(borders.top)) else c
            } else cells
            OdfTableRow(decorated)
        }
        return OdfTable(columns = acc.columns, rows = rows, headerRowCount = acc.headerRows)
    }

    /** Parses one table row into [grid], resolving vertical merges. Returns true if it's a header row. */
    private fun parseRow(
        parser: XmlPullParser, ctx: OoxmlDocx.DocxCtx,
        grid: MutableList<MutableList<OdfTableCell>>, vAnchors: HashMap<Int, Pair<Int, Int>>
    ): Boolean {
        val depth = parser.depth
        val rowIdx = grid.size
        val cells = mutableListOf<OdfTableCell>()
        val row = RowAcc()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyRowTag(parser, ctx, row, grid, cells, rowIdx, vAnchors)
            e = parser.next()
        }
        grid.add(cells)
        return row.isHeader
    }

    /** Row accumulation state. */
    private class RowAcc(
        var isHeader: Boolean = false,
        var colCursor: Int = 0,
    )

    /** Apply one row child tag. */
    private fun applyRowTag(
        parser: XmlPullParser,
        ctx: OoxmlDocx.DocxCtx,
        row: RowAcc,
        grid: MutableList<MutableList<OdfTableCell>>,
        cells: MutableList<OdfTableCell>,
        rowIdx: Int,
        vAnchors: HashMap<Int, Pair<Int, Int>>,
    ) {
        when (parser.name) {
            "tblHeader" -> if (OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))) row.isHeader = true
            "tc" -> applyTableCell(parser, ctx, row, grid, cells, rowIdx, vAnchors)
        }
    }

    /** Apply one table cell with vertical-merge resolution. */
    private fun applyTableCell(
        parser: XmlPullParser,
        ctx: OoxmlDocx.DocxCtx,
        row: RowAcc,
        grid: MutableList<MutableList<OdfTableCell>>,
        cells: MutableList<OdfTableCell>,
        rowIdx: Int,
        vAnchors: HashMap<Int, Pair<Int, Int>>,
    ) {
        val cell = parseCell(parser, ctx)
        val span = cell.colSpan
        if (cell.vMergeContinue) {
            vAnchors[row.colCursor]?.let { (ar, ac) ->
                val anchor = grid.getOrNull(ar)?.getOrNull(ac)
                if (anchor != null) grid[ar][ac] = anchor.copy(rowSpan = anchor.rowSpan + 1)
            }
            repeat(span) { cells.add(OdfTableCell(isCovered = true)) }
        } else {
            val myCol = cells.size
            cells.add(cell.toModel())
            repeat(span - 1) { cells.add(OdfTableCell(isCovered = true)) }
            if (cell.vMergeRestart) vAnchors[row.colCursor] = rowIdx to myCol else vAnchors.remove(row.colCursor)
        }
        row.colCursor += span
    }

    private class CellAccum(
        val paragraphs: List<OdfParagraph>,
        val colSpan: Int,
        val backgroundColor: Long?,
        val borders: OdfBorders?,
        val vAlign: String?,
        val vMergeRestart: Boolean,
        val vMergeContinue: Boolean
    ) {
        fun toModel() = OdfTableCell(
            paragraphs = paragraphs.ifEmpty { listOf(OdfParagraph(listOf(OdfSpan("")))) },
            colSpan = colSpan,
            backgroundColor = backgroundColor,
            borders = borders?.takeIf { !it.isEmpty() },
            borderColor = borders?.let { OdfBorders.renderColor(it.top ?: it.left) },
            verticalAlign = vAlign
        )
    }

    private fun parseCell(parser: XmlPullParser, ctx: OoxmlDocx.DocxCtx): CellAccum {
        val depth = parser.depth
        val cell = CellAcc()
        val dummy = mutableListOf<OdfContentBlock>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tc")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyCellTag(parser, ctx, cell, dummy)
            e = parser.next()
        }
        return CellAccum(
            cell.paras, cell.colSpan, cell.bg, cell.borders, cell.vAlign,
            cell.vMergeRestart, cell.vMergeContinue)
    }

    /** Cell accumulation state. */
    private class CellAcc(
        val paras: MutableList<OdfParagraph> = mutableListOf(),
        var colSpan: Int = 1,
        var bg: Long? = null,
        var borders: OdfBorders? = null,
        var vAlign: String? = null,
        var vMergeRestart: Boolean = false,
        var vMergeContinue: Boolean = false,
    )

    /** Apply one table-cell child tag. */
    private fun applyCellTag(
        parser: XmlPullParser,
        ctx: OoxmlDocx.DocxCtx,
        cell: CellAcc,
        dummy: MutableList<OdfContentBlock>,
    ) {
        when (parser.name) {
            "gridSpan" -> cell.colSpan = OoxmlXml.attr(parser, "val")?.toIntOrNull() ?: 1
            "shd" -> cell.bg = OoxmlUnits.hexColor(OoxmlXml.attr(parser, "fill")) ?: cell.bg
            "tcBorders" -> cell.borders = OoxmlDocxStyles.parseBorders(parser, "tcBorders")
            "vAlign" -> cell.vAlign = vAlignOf(parser)
            "vMerge" -> applyVMerge(parser, cell)
            "p" -> applyCellParagraph(parser, ctx, cell, dummy)
            "tbl" -> applyNestedTable(parser, ctx, cell)
        }
    }

    /** Vertical alignment from a w:vAlign tag. */
    private fun vAlignOf(parser: XmlPullParser): String = when (OoxmlXml.attr(
        parser,
        "val")) { "center" -> "middle"; "bottom" -> "bottom"; else -> "top" }

    /** Vertical-merge marker. */
    private fun applyVMerge(parser: XmlPullParser, cell: CellAcc) {
        val v = OoxmlXml.attr(
            parser,
            "val")
        if (v == null || v == "continue") cell.vMergeContinue = true else cell.vMergeRestart = true
    }

    /** Cell paragraph → model paragraphs. */
    private fun applyCellParagraph(
        parser: XmlPullParser,
        ctx: OoxmlDocx.DocxCtx,
        cell: CellAcc,
        dummy: MutableList<OdfContentBlock>,
    ) {
        val before = dummy.size
        OoxmlDocx.parseParagraph(parser, ctx, dummy)
        for (i in before until dummy.size) {
            (dummy[i] as? OdfContentBlock.Paragraph)?.let { cell.paras.add(it.paragraph) }
        }
    }

    /** Nested table → flattened text paragraphs (best-effort). */
    private fun applyNestedTable(parser: XmlPullParser, ctx: OoxmlDocx.DocxCtx, cell: CellAcc) {
        val nested = parseTable(parser, ctx)
        for (r in nested.rows) for (c in r.cells) if (!c.isCovered) cell.paras.addAll(c.paragraphs)
    }
}
