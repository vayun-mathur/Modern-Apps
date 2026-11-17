package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.ChartType
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfChartSeries
import org.xmlpull.v1.XmlPullParser

/**
 * Parses a DrawingML chart part (`c:chart` in chart1.xml) into the ODF [OdfChart] model (Phase C1).
 * Shared by docx/xlsx/pptx. Covers bar/line/pie/area/scatter/doughnut/radar/bubble charts, series
 * names/values/categories, per-series color and data labels, chart title, legend, and axis titles.
 */
internal object OoxmlChart {

    private class SeriesAccum {
        var name: String? = null
        var color: Long? = null
        var dataLabels = false
        val cats = sortedMapOf<Int, String>()
        val vals = sortedMapOf<Int, Float>()
    }

    /** Parses chart XML; returns null if no plottable series were found. */
    fun parse(xml: String, theme: OoxmlTheme = OoxmlTheme.DEFAULT): OdfChart? {
        val parser = OoxmlXml.newParser(xml)
        val acc = ChartAcc()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) applyChartStart(parser, acc, theme)
            else if (e == XmlPullParser.END_TAG) applyChartEnd(parser, acc)
            e = parser.next()
        }
        return buildChart(acc)
    }

    private class ChartAcc(
        var type: ChartType = ChartType.BAR,
        var stacked: Boolean = false,
        var barDirCol: Boolean = true,
        var typeSeen: Boolean = false,
        var title: String? = null,
        var xAxisTitle: String? = null,
        var yAxisTitle: String? = null,
        var legend: Boolean = false,
        var inCatAx: Boolean = false,
        var inValAx: Boolean = false,
        val series: MutableList<SeriesAccum> = mutableListOf(),
    )

    private fun applyChartStart(parser: XmlPullParser, acc: ChartAcc, theme: OoxmlTheme) {
        if (applyChartTypeTag(parser, acc)) return
        when (parser.name) {
            "barDir" -> acc.barDirCol = OoxmlXml.attr(parser, "val") != "bar"
            "grouping" -> applyGroupingTag(parser, acc)
            "catAx" -> acc.inCatAx = true
            "valAx" -> acc.inValAx = true
            "legend" -> acc.legend = true
            "title" -> applyChartTitleTag(parser, acc)
            "ser" -> acc.series.add(parseSeries(parser, theme))
        }
    }

    private fun applyChartTypeTag(parser: XmlPullParser, acc: ChartAcc): Boolean {
        val t = chartTypeFor(parser.name) ?: return false
        if (!acc.typeSeen) { acc.type = t; acc.typeSeen = true }
        return true
    }

    private fun chartTypeFor(name: String): ChartType? = when (name) {
        "barChart", "bar3DChart" -> ChartType.BAR
        "lineChart", "line3DChart" -> ChartType.LINE
        "pieChart", "pie3DChart", "ofPieChart" -> ChartType.PIE
        "doughnutChart" -> ChartType.DONUT
        "areaChart", "area3DChart" -> ChartType.AREA
        "scatterChart" -> ChartType.SCATTER
        "radarChart" -> ChartType.RADAR
        "bubbleChart" -> ChartType.BUBBLE
        else -> null
    }

    private fun applyGroupingTag(parser: XmlPullParser, acc: ChartAcc) {
        val g = OoxmlXml.attr(parser, "val")
        if (g == "stacked" || g == "percentStacked") acc.stacked = true
    }

    private fun applyChartTitleTag(parser: XmlPullParser, acc: ChartAcc) {
        val t = readTitleText(parser)
        when {
            acc.inCatAx -> acc.xAxisTitle = t
            acc.inValAx -> acc.yAxisTitle = t
            else -> acc.title = t
        }
    }

    private fun applyChartEnd(parser: XmlPullParser, acc: ChartAcc) {
        when (parser.name) { "catAx" -> acc.inCatAx = false; "valAx" -> acc.inValAx = false }
    }

    private fun buildChart(acc: ChartAcc): OdfChart? {
        if (acc.series.isEmpty() || acc.series.all { it.vals.isEmpty() }) return null
        var type = acc.type
        if (type == ChartType.BAR && acc.stacked) type = ChartType.STACKED_BAR
        // Preserve idx alignment: densify by point index so a series missing a point (idx 0,1,3)
        // stays aligned with its categories instead of shifting later points left.
        val maxIdx = acc.series.flatMap { it.cats.keys + it.vals.keys }.maxOrNull() ?: -1
        val catSource = acc.series.maxByOrNull { it.cats.size }?.cats ?: sortedMapOf()
        val categories = (0..maxIdx).map { catSource[it] ?: "" }
        val odfSeries = acc.series.mapIndexed { i, s -> buildSeries(s, i, maxIdx) }
        return OdfChart(
            type = type,
            categories = categories,
            series = odfSeries,
            title = acc.title,
            legend = acc.legend,
            xAxisTitle = if (acc.barDirCol) acc.xAxisTitle else acc.yAxisTitle,
            yAxisTitle = if (acc.barDirCol) acc.yAxisTitle else acc.xAxisTitle,
            stacked = acc.stacked
        )
    }

    private fun buildSeries(s: SeriesAccum, i: Int, maxIdx: Int): OdfChartSeries {
        return OdfChartSeries(
            name = s.name ?: "Series ${i + 1}",
            values = (0..maxIdx).map { s.vals[it] ?: 0f },
            color = s.color,
            dataLabels = s.dataLabels
        )
    }

    private class SeriesState(
        var cache: String? = null,
        var inTx: Boolean = false,
        var inSpPr: Boolean = false,
        var inLn: Boolean = false,
        var inDLbls: Boolean = false,
        val txt: StringBuilder = StringBuilder(),
        var curIdx: Int = 0,
    )

    private fun parseSeries(parser: XmlPullParser, theme: OoxmlTheme): SeriesAccum {
        val s = SeriesAccum()
        val depth = parser.depth
        val st = SeriesState()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "ser")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applySeriesStart(parser, theme, s, st)
            else if (e == XmlPullParser.END_TAG) applySeriesEnd(parser, s, st)
            e = parser.next()
        }
        return s
    }

    private fun applySeriesStart(parser: XmlPullParser, theme: OoxmlTheme, s: SeriesAccum, st: SeriesState) {
        when (parser.name) {
            "tx" -> st.inTx = true
            "cat", "xVal" -> st.cache = "cat"
            "val", "yVal" -> st.cache = "val"
            "spPr" -> st.inSpPr = true
            "ln" -> st.inLn = true
            "dLbls" -> st.inDLbls = true
            "showVal" -> applyShowValTag(parser, s, st)
            "pt" -> st.curIdx = OoxmlXml.attr(parser, "idx")?.toIntOrNull() ?: st.curIdx
            "v" -> applySeriesValueTag(parser, s, st)
            "srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr" -> applySeriesColorTag(parser, theme, s, st)
        }
    }

    private fun applyShowValTag(parser: XmlPullParser, s: SeriesAccum, st: SeriesState) {
        if (st.inDLbls && OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))) s.dataLabels = true
    }

    private fun applySeriesValueTag(parser: XmlPullParser, s: SeriesAccum, st: SeriesState) {
        val v = OoxmlXml.readElementText(parser, "v")
        when {
            st.inTx -> st.txt.append(v)
            st.cache == "cat" -> s.cats[st.curIdx] = v
            st.cache == "val" -> v.toFloatOrNull()?.let { s.vals[st.curIdx] = it }
        }
    }

    private fun applySeriesColorTag(parser: XmlPullParser, theme: OoxmlTheme, s: SeriesAccum, st: SeriesState) {
        // Capture the first color in spPr — for line/scatter series it lives inside <a:ln>.
        if (st.inSpPr && s.color == null) s.color = OoxmlColor.parse(parser, theme)
    }

    private fun applySeriesEnd(parser: XmlPullParser, s: SeriesAccum, st: SeriesState) {
        when (parser.name) {
            "tx" -> finishTxTag(s, st)
            "cat", "val", "xVal", "yVal" -> st.cache = null
            "spPr" -> st.inSpPr = false
            "ln" -> st.inLn = false
            "dLbls" -> st.inDLbls = false
        }
    }

    private fun finishTxTag(s: SeriesAccum, st: SeriesState) {
        st.inTx = false
        if (s.name == null && st.txt.isNotBlank()) s.name = st.txt.toString()
        st.txt.clear()
    }

    /** Reads all a:t / c:v text inside a `c:title` element (consumes through its END_TAG). */
    private fun readTitleText(parser: XmlPullParser): String {
        val depth = parser.depth
        val sb = StringBuilder()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "title")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && (parser.name == "t" || parser.name == "v")) {
                sb.append(OoxmlXml.readElementText(parser, parser.name))
            }
            e = parser.next()
        }
        return sb.toString().trim()
    }
}
