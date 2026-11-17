package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.ChartType
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfChartSeries
import org.xmlpull.v1.XmlPullParser

// --- Embedded charts ---

internal fun OdfParser.parseChart(xml: String): OdfChart? {
    val chartStyles = parseChartStyles(xml)
    val parser = newParser(xml)
    val acc = ChartAcc()
    var eventType = parser.eventType

    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> applyChartStart(parser, acc)
            XmlPullParser.TEXT ->
                if (acc.inCell) acc.cellText.append(parser.text)
                else if (acc.captureTarget != null) acc.titleBuf.append(parser.text)
            XmlPullParser.END_TAG -> applyChartEnd(parser, acc)
        }
        eventType = parser.next()
    }
    if (acc.rows.size < 2) return null
    return buildChart(acc, chartStyles)
}

/** Chart accumulation state. */
private class ChartAcc(
    var chartClass: String? = null,
    var stacked: Boolean = false,
    val seriesStyleNames: MutableList<String?> = mutableListOf(),
    var inTable: Boolean = false,
    var inCell: Boolean = false,
    var curRow: MutableList<Pair<String, Float?>>? = null,
    var cellText: StringBuilder = StringBuilder(),
    var cellValue: Float? = null,
    val rows: MutableList<List<Pair<String, Float?>>> = mutableListOf(),
    var legend: Boolean = false,
    var title: String? = null,
    var subtitle: String? = null,
    var xAxisTitle: String? = null,
    var yAxisTitle: String? = null,
    var axisDim: String? = null,
    var captureTarget: String? = null,
    val titleBuf: StringBuilder = StringBuilder(),
)

/** Apply one chart start tag. */
private fun OdfParser.applyChartStart(parser: XmlPullParser, acc: ChartAcc) {
    applyChartMetaTag(parser, acc)
    applyChartTableTag(parser, acc)
}

/** Chart/legend/series/axis/title tags. */
private fun OdfParser.applyChartMetaTag(parser: XmlPullParser, acc: ChartAcc) {
    when (parser.name) {
        "chart" -> if (acc.chartClass == null) acc.chartClass = getAttr(parser, "class")
        "legend" -> acc.legend = true
        "plot-area" -> applyStackedAttr(parser, acc)
        "chart-properties" -> applyStackedAttr(parser, acc)
        "series" -> acc.seriesStyleNames.add(getAttr(parser, "style-name"))
        "axis" -> acc.axisDim = getAttr(parser, "dimension")
        "title" -> beginTitleCapture(acc)
        "subtitle" -> { acc.captureTarget = "sub"; acc.titleBuf.setLength(0) }
    }
}

/** Stacked flag from a plot-area/chart-properties tag. */
private fun OdfParser.applyStackedAttr(parser: XmlPullParser, acc: ChartAcc) {
    if (getAttr(parser, "stacked") == "true" || getAttr(parser, "percentage") == "true") acc.stacked = true
}

/** Begin title capture for the current axis. */
private fun beginTitleCapture(acc: ChartAcc) {
    acc.captureTarget = when (acc.axisDim) { "x" -> "x"; "y" -> "y"; else -> "main" }
    acc.titleBuf.setLength(0)
}

/** Local-table cell tags. */
private fun OdfParser.applyChartTableTag(parser: XmlPullParser, acc: ChartAcc) {
    when (parser.name) {
        "table" -> if (getAttr(parser, "name") == "local-table") acc.inTable = true
        "table-row" -> if (acc.inTable) acc.curRow = mutableListOf()
        "table-cell" -> beginChartCell(parser, acc)
    }
}

/** Begin one local-table cell. */
private fun OdfParser.beginChartCell(parser: XmlPullParser, acc: ChartAcc) {
    if (!acc.inTable || acc.curRow == null) return
    acc.inCell = true
    acc.cellText = StringBuilder()
    acc.cellValue = getAttr(parser, "value")?.toFloatOrNull()
}

/** Apply one chart end tag. */
private fun applyChartEnd(parser: XmlPullParser, acc: ChartAcc) {
    when (parser.name) {
        "title" -> {
            val t = acc.titleBuf.toString().trim().ifEmpty { null }
            when (acc.captureTarget) {
                "x" -> acc.xAxisTitle = t
                "y" -> acc.yAxisTitle = t
                "main" -> acc.title = t
            }
            acc.captureTarget = null
        }
        "subtitle" -> { acc.subtitle = acc.titleBuf.toString().trim().ifEmpty { null }; acc.captureTarget = null }
        "axis" -> acc.axisDim = null
        "table" -> if (acc.inTable) acc.inTable = false
        "table-cell" -> if (acc.inCell) {
            acc.curRow?.add(acc.cellText.toString().trim() to acc.cellValue)
            acc.inCell = false
        }
        "table-row" -> if (acc.curRow != null) { acc.rows.add(acc.curRow!!); acc.curRow = null }
    }
}

/** Build the chart from accumulated rows. */
private fun OdfParser.buildChart(
    acc: ChartAcc,
    chartStyles: Map<String, Pair<Long?, Boolean>>,
): OdfChart? {
    val header = acc.rows[0]
    val seriesCount = (header.size - 1).coerceAtLeast(0)
    if (seriesCount == 0) return null
    val seriesNames = (1..seriesCount).map {
        header.getOrNull(it)?.first?.ifEmpty { "Series $it" } ?: "Series $it"
    }
    val seriesValues = List(seriesCount) { mutableListOf<Float>() }
    val categories = mutableListOf<String>()
    for (i in 1 until acc.rows.size) {
        val r = acc.rows[i]
        categories.add(r.getOrNull(0)?.first ?: "")
        for (j in 0 until seriesCount) {
            val cell = r.getOrNull(j + 1)
            seriesValues[j].add(cell?.second ?: cell?.first?.toFloatOrNull() ?: 0f)
        }
    }
    val type = chartTypeFor(acc.chartClass, acc.stacked)
    val series = seriesNames.mapIndexed { idx, n ->
        val styleAttrs = acc.seriesStyleNames.getOrNull(idx)?.let { chartStyles[it] }
        OdfChartSeries(n, seriesValues[idx], color = styleAttrs?.first, dataLabels = styleAttrs?.second ?: false)
    }
    return if (categories.isEmpty()) null else OdfChart(
        type,
        categories,
        series,
        acc.title,
        acc.subtitle,
        acc.legend,
        acc.xAxisTitle,
        acc.yAxisTitle,
        stacked = acc.stacked)
}

/** Chart type from the class + stacked flag. */
private fun chartTypeFor(chartClass: String?, stacked: Boolean): ChartType = when {
    chartClass?.contains("line") == true -> ChartType.LINE
    chartClass?.contains("scatter") == true -> ChartType.SCATTER
    chartClass?.contains("ring") == true -> ChartType.DONUT
    chartClass?.contains("circle") == true -> ChartType.PIE
    chartClass?.contains("radar") == true -> ChartType.RADAR
    chartClass?.contains("bubble") == true -> ChartType.BUBBLE
    chartClass?.contains("area") == true -> ChartType.AREA
    stacked && chartClass?.contains("bar") == true -> ChartType.STACKED_BAR
    else -> ChartType.BAR
}

/** Chart-style accumulation state. */
private class ChartStyleAcc(
    var curName: String? = null,
    var fill: Long? = null,
    var dataLabels: Boolean = false,
    val depthStack: ArrayDeque<Int> = ArrayDeque(),
    val map: MutableMap<String, Pair<Long?, Boolean>> = mutableMapOf(),
)

/** Parses chart automatic styles: chart style-name -> (fill color, data-labels enabled). (Phase 5) */
internal fun OdfParser.parseChartStyles(xml: String): Map<String, Pair<Long?, Boolean>> {
    val parser = newParser(xml)
    val acc = ChartStyleAcc()
    var e = parser.eventType
    while (e != XmlPullParser.END_DOCUMENT) {
        when (e) {
            XmlPullParser.START_TAG -> applyChartStyleStart(parser, acc)
            XmlPullParser.END_TAG -> applyChartStyleEnd(parser, acc)
        }
        e = parser.next()
    }
    return acc.map
}

/** Apply one chart-style start tag. */
private fun OdfParser.applyChartStyleStart(parser: XmlPullParser, acc: ChartStyleAcc) {
    when (parser.name) {
        "style" -> beginChartStyle(parser, acc)
        "graphic-properties" -> applyChartStyleFill(parser, acc)
        "chart-properties" -> applyChartStyleLabels(parser, acc)
    }
}

/** Begin one chart style. */
private fun OdfParser.beginChartStyle(parser: XmlPullParser, acc: ChartStyleAcc) {
    if (getAttr(parser, "family") != "chart" && getAttr(parser, "family") != null) return
    getAttr(parser, "name")?.let {
        acc.curName = it
        acc.fill = null
        acc.dataLabels = false
        acc.depthStack.addLast(parser.depth)
    }
}

/** Fill color within a chart style. */
private fun OdfParser.applyChartStyleFill(parser: XmlPullParser, acc: ChartStyleAcc) {
    if (acc.curName == null) return
    getAttr(parser, "fill-color")?.let { acc.fill = parseColor(it) }
}

/** Data-label flags within a chart style. */
private fun OdfParser.applyChartStyleLabels(parser: XmlPullParser, acc: ChartStyleAcc) {
    if (acc.curName == null) return
    val dln = getAttr(parser, "data-label-number")
    if (dln != null && dln != "none" || getAttr(parser, "data-label-text") == "true") {
        acc.dataLabels = true
    }
}

/** Apply one chart-style end tag. */
private fun applyChartStyleEnd(parser: XmlPullParser, acc: ChartStyleAcc) {
    val isStyleEnd = parser.name == "style" &&
        acc.curName != null &&
        acc.depthStack.isNotEmpty() &&
        parser.depth == acc.depthStack.last()
    if (!isStyleEnd) return
    acc.depthStack.removeLast()
    acc.map[acc.curName!!] = acc.fill to acc.dataLabels
    acc.curName = null
}

internal fun OdfParser.parseFormulaText(xml: String): String? {
    if (!xml.contains("math")) return null
    val ann = Regex("<annotation[^>]*>(.*?)</annotation>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
    if (!ann.isNullOrBlank()) return unescapeXml(ann.trim())
    var toks = Regex(
        "<m:(mi|mn|mo)[^>]*>(.*?)</m:(mi|mn|mo)>",
        RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[2] }.toList()
    if (toks.isEmpty()) toks = Regex(
        "<(mi|mn|mo)[^>]*>(.*?)</(mi|mn|mo)>",
        RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[2] }.toList()
    val joined = toks.joinToString(" ").trim()
    return if (joined.isBlank()) null else unescapeXml(joined)
}

internal fun OdfParser.unescapeXml(s: String): String = s
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&apos;", "'").replace("&amp;", "&")
