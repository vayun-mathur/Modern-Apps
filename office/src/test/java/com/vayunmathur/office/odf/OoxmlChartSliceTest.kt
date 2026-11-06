package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.ChartType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/4 (Ooxml*): tests for [OoxmlChart] — chart type mapping, series/categories parsing,
 * titles/legend/axes, per-series colors and data labels, stacked promotion, sparse idx alignment.
 */
class OoxmlChartSliceTest {

    private fun chart(
        typeTag: String = "<barChart><barDir val=\"col\"/><grouping val=\"clustered\"/>",
        typeClose: String = "</barChart>",
        body: String = SERIES1,
        extra: String = ""
    ) = "<chartSpace xmlns:c=\"c\" xmlns:a=\"a\"><chart><plotArea>$typeTag$body$typeClose</plotArea>$extra</chart></chartSpace>"

    companion object {
        private const val SERIES1 =
            "<ser><tx><v>S1</v></tx>" +
                "<cat><strRef><strCache><pt idx=\"0\"><v>A</v></pt><pt idx=\"1\"><v>B</v></pt></strCache></strRef></cat>" +
                "<val><numRef><numCache><pt idx=\"0\"><v>10</v></pt><pt idx=\"1\"><v>20</v></pt></numCache></numRef></val>" +
                "</ser>"
    }

    @Test fun barChartBasics() {
        val c = OoxmlChart.parse(chart())!!
        assertEquals(ChartType.BAR, c.type)
        assertEquals(listOf("A", "B"), c.categories)
        assertEquals(1, c.series.size)
        assertEquals("S1", c.series[0].name)
        assertEquals(listOf(10f, 20f), c.series[0].values)
        assertFalse(c.legend)
        assertFalse(c.stacked)
    }

    @Test fun chartTypeMapping() {
        val cases = mapOf(
            "barChart" to ChartType.BAR, "bar3DChart" to ChartType.BAR,
            "lineChart" to ChartType.LINE, "line3DChart" to ChartType.LINE,
            "pieChart" to ChartType.PIE, "pie3DChart" to ChartType.PIE, "ofPieChart" to ChartType.PIE,
            "doughnutChart" to ChartType.DONUT,
            "areaChart" to ChartType.AREA, "area3DChart" to ChartType.AREA,
            "scatterChart" to ChartType.SCATTER,
            "radarChart" to ChartType.RADAR,
            "bubbleChart" to ChartType.BUBBLE)
        for ((tag, expected) in cases) {
            val open = if (tag == "scatterChart" || tag == "bubbleChart") "<$tag>" else "<$tag>"
            val c = OoxmlChart.parse(chart(open, "</$tag>"))!!
            assertEquals(expected, c.type, "tag $tag")
        }
    }

    @Test fun scatterUsesXYVals() {
        val body = "<ser><tx><v>pts</v></tx>" +
            "<xVal><numRef><numCache><pt idx=\"0\"><v>1</v></pt></numCache></numRef></xVal>" +
            "<yVal><numRef><numCache><pt idx=\"0\"><v>5</v></pt><pt idx=\"1\"><v>6</v></pt></numCache></numRef></yVal></ser>"
        val c = OoxmlChart.parse(chart("<scatterChart>", "</scatterChart>", body))!!
        assertEquals(ChartType.SCATTER, c.type)
        assertEquals(listOf(5f, 6f), c.series[0].values)
        assertEquals(listOf("1", ""), c.categories)
    }

    @Test fun emptyChartIsNull() {
        assertNull(OoxmlChart.parse("<chartSpace><chart><plotArea><barChart/></plotArea></chart></chartSpace>"))
        // Series with no numeric values is not plottable.
        val noVals = "<ser><tx><v>S</v></tx><cat><strRef><strCache><pt idx=\"0\"><v>A</v></pt></strCache></strRef></cat></ser>"
        assertNull(OoxmlChart.parse(chart(body = noVals)))
    }

    @Test fun stackedBarPromotesType() {
        val c = OoxmlChart.parse(
            chart("<barChart><barDir val=\"col\"/><grouping val=\"stacked\"/>"))!!
        assertEquals(ChartType.STACKED_BAR, c.type)
        assertTrue(c.stacked)
        val pct = OoxmlChart.parse(
            chart("<barChart><barDir val=\"bar\"/><grouping val=\"percentStacked\"/>"))!!
        assertEquals(ChartType.STACKED_BAR, pct.type)
    }

    @Test fun titleLegendAndAxes() {
        val extra = "<title><tx><rich><p><r><t>My Title</t></r></p></rich></tx></title>" +
            "<legend><legendPos val=\"r\"/></legend>" +
            "<catAx><title><tx><rich><p><r><t>Months</t></r></p></rich></tx></title></catAx>" +
            "<valAx><title><tx><rich><p><r><t>Sales</t></r></p></rich></tx></title></valAx>"
        val c = OoxmlChart.parse(chart(extra = extra))!!
        assertEquals("My Title", c.title)
        assertTrue(c.legend)
        assertEquals("Months", c.xAxisTitle)
        assertEquals("Sales", c.yAxisTitle)
    }

    @Test fun barDirSwapsAxes() {
        val extra = "<catAx><title><tx><rich><p><r><t>C</t></r></p></rich></tx></title></catAx>" +
            "<valAx><title><tx><rich><p><r><t>V</t></r></p></rich></tx></title></valAx>"
        val c = OoxmlChart.parse(chart("<barChart><barDir val=\"bar\"/>", extra = extra))!!
        assertEquals("V", c.xAxisTitle)
        assertEquals("C", c.yAxisTitle)
    }

    @Test fun seriesColorAndDataLabels() {
        val body = "<ser><tx><v>S</v></tx>" +
            "<spPr><solidFill><srgbClr val=\"FF0000\"/></solidFill></spPr>" +
            "<dLbls><showVal val=\"1\"/></dLbls>" +
            "<val><numRef><numCache><pt idx=\"0\"><v>3</v></pt></numCache></numRef></val></ser>"
        val c = OoxmlChart.parse(chart(body = body))!!
        assertEquals(0xFFFF0000L, c.series[0].color)
        assertTrue(c.series[0].dataLabels)
    }

    @Test fun firstChartTypeWins() {
        val xml = "<chartSpace><chart><plotArea><barChart>$SERIES1</barChart>" +
            "<lineChart>$SERIES1</lineChart></plotArea></chart></chartSpace>"
        assertEquals(ChartType.BAR, OoxmlChart.parse(xml)!!.type)
    }

    @Test fun missingSeriesNameDefaults() {
        val body = "<ser><val><numRef><numCache><pt idx=\"0\"><v>1</v></pt></numCache></numRef></val></ser>"
        val c = OoxmlChart.parse(chart(body = body))!!
        assertEquals("Series 1", c.series[0].name)
    }

    @Test fun sparseIdxStaysAligned() {
        val two = "<ser><tx><v>two</v></tx>" +
            "<cat><strRef><strCache><pt idx=\"0\"><v>a</v></pt><pt idx=\"2\"><v>c</v></pt></strCache></strRef></cat>" +
            "<val><numRef><numCache><pt idx=\"0\"><v>1</v></pt><pt idx=\"2\"><v>3</v></pt></numCache></numRef></val></ser>"
        val c = OoxmlChart.parse(chart(body = two))!!
        assertEquals(listOf("a", "", "c"), c.categories)
        assertEquals(listOf(1f, 0f, 3f), c.series[0].values)
    }

    @Test fun nonNumericValueIgnored() {
        val body = "<ser><tx><v>S</v></tx>" +
            "<val><numRef><numCache><pt idx=\"0\"><v>abc</v></pt><pt idx=\"1\"><v>2</v></pt></numCache></numRef></val></ser>"
        val c = OoxmlChart.parse(chart(body = body))!!
        assertEquals(listOf(0f, 2f), c.series[0].values)
    }
}
