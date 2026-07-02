package com.vayunmathur.office.odf

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
        var type = ChartType.BAR
        var stacked = false
        var barDirCol = true
        var typeSeen = false
        var title: String? = null
        var xAxisTitle: String? = null
        var yAxisTitle: String? = null
        var legend = false
        var inCatAx = false
        var inValAx = false
        val series = mutableListOf<SeriesAccum>()

        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                when (val n = parser.name) {
                    "barChart", "bar3DChart" -> { if (!typeSeen) { type = ChartType.BAR; typeSeen = true } }
                    "lineChart", "line3DChart" -> { if (!typeSeen) { type = ChartType.LINE; typeSeen = true } }
                    "pieChart", "pie3DChart", "ofPieChart" -> { if (!typeSeen) { type = ChartType.PIE; typeSeen = true } }
                    "doughnutChart" -> { if (!typeSeen) { type = ChartType.DONUT; typeSeen = true } }
                    "areaChart", "area3DChart" -> { if (!typeSeen) { type = ChartType.AREA; typeSeen = true } }
                    "scatterChart" -> { if (!typeSeen) { type = ChartType.SCATTER; typeSeen = true } }
                    "radarChart" -> { if (!typeSeen) { type = ChartType.RADAR; typeSeen = true } }
                    "bubbleChart" -> { if (!typeSeen) { type = ChartType.BUBBLE; typeSeen = true } }
                    "barDir" -> barDirCol = OoxmlXml.attr(parser, "val") != "bar"
                    "grouping" -> { val g = OoxmlXml.attr(parser, "val"); if (g == "stacked" || g == "percentStacked") stacked = true }
                    "catAx" -> inCatAx = true
                    "valAx" -> inValAx = true
                    "legend" -> legend = true
                    "title" -> {
                        val t = readTitleText(parser)
                        when {
                            inCatAx -> xAxisTitle = t
                            inValAx -> yAxisTitle = t
                            else -> title = t
                        }
                    }
                    "ser" -> series.add(parseSeries(parser, theme))
                }
            } else if (e == XmlPullParser.END_TAG) {
                when (parser.name) { "catAx" -> inCatAx = false; "valAx" -> inValAx = false }
            }
            e = parser.next()
        }

        if (series.isEmpty() || series.all { it.vals.isEmpty() }) return null
        if (type == ChartType.BAR && stacked) type = ChartType.STACKED_BAR

        val categories = series.maxByOrNull { it.cats.size }?.cats?.values?.toList() ?: emptyList()
        val odfSeries = series.mapIndexed { i, s ->
            OdfChartSeries(
                name = s.name ?: "Series ${i + 1}",
                values = s.vals.values.toList(),
                color = s.color,
                dataLabels = s.dataLabels
            )
        }
        return OdfChart(
            type = type,
            categories = categories,
            series = odfSeries,
            title = title,
            legend = legend,
            xAxisTitle = if (barDirCol) xAxisTitle else yAxisTitle,
            yAxisTitle = if (barDirCol) yAxisTitle else xAxisTitle,
            stacked = stacked
        )
    }

    private fun parseSeries(parser: XmlPullParser, theme: OoxmlTheme): SeriesAccum {
        val s = SeriesAccum()
        val depth = parser.depth
        var cache: String? = null      // "cat" | "val" | null
        var inTx = false
        var inSpPr = false
        var inLn = false
        var inDLbls = false
        val txt = StringBuilder()
        var curIdx = 0
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "ser")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "tx" -> inTx = true
                "cat", "xVal" -> cache = "cat"
                "val", "yVal" -> cache = "val"
                "spPr" -> inSpPr = true
                "ln" -> inLn = true
                "dLbls" -> inDLbls = true
                "showVal" -> if (inDLbls && OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))) s.dataLabels = true
                "pt" -> curIdx = OoxmlXml.attr(parser, "idx")?.toIntOrNull() ?: curIdx
                "v" -> {
                    val v = OoxmlXml.readElementText(parser, "v")
                    when {
                        inTx -> txt.append(v)
                        cache == "cat" -> s.cats[curIdx] = v
                        cache == "val" -> v.toFloatOrNull()?.let { s.vals[curIdx] = it }
                    }
                }
                "srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr" ->
                    if (inSpPr && !inLn && s.color == null) s.color = OoxmlColor.parse(parser, theme)
            } else if (e == XmlPullParser.END_TAG) when (parser.name) {
                "tx" -> { inTx = false; if (s.name == null && txt.isNotBlank()) s.name = txt.toString(); txt.clear() }
                "cat", "val", "xVal", "yVal" -> cache = null
                "spPr" -> inSpPr = false
                "ln" -> inLn = false
                "dLbls" -> inDLbls = false
            }
            e = parser.next()
        }
        return s
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
