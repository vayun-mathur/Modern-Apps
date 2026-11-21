package com.vayunmathur.office.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.odf.ChartType
import com.vayunmathur.library.ui.odf.OdfChart

private val chartPalette = listOf(
    Color(0xFF1F6FC0), Color(0xFFE8551E), Color(0xFFF2B600),
    Color(0xFF3FA34D), Color(0xFF8E44AD), Color(0xFF16A2B8), Color(0xFFD81B60)
)


internal fun formatAxis(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)

@Composable
internal fun OdfChartView(chart: OdfChart, onClick: () -> Unit = {}) =
    OdfChartView(chart, onClick, Modifier)

/** [modifier] overload (kept separate so trailing-lambda call sites keep binding to onClick). */
@Composable
internal fun OdfChartView(chart: OdfChart, onClick: () -> Unit, modifier: Modifier) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    Column(Modifier.fillMaxWidth().then(modifier).clickable { onClick() }.padding(vertical = 12.dp)) {
        chart.title?.let { Text(
            it,
            style = MaterialTheme.typography.titleSmall,
            color = onSurface,
            modifier = Modifier.padding(bottom = 4.dp)) }
        // Legend
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            chart.series.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(chartPalette[i % chartPalette.size]))
                    Spacer(Modifier.width(4.dp))
                    Text(s.name, style = MaterialTheme.typography.labelMedium, color = onSurface)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val labelArgb = onSurface.toArgb()
        Canvas(Modifier.fillMaxWidth().height(240.dp)) {
            val allVals = chart.series.flatMap { it.values }
            // Include 0 in the range so negative values plot below the baseline instead of vanishing.
            val maxV = maxOf(allVals.maxOrNull() ?: 1f, 0f).let { if (it <= 0f) 1f else it }
            val minV = minOf(allVals.minOrNull() ?: 0f, 0f)
            val range = (maxV - minV).coerceAtLeast(1f)
            val leftPad = 72f; val bottomPad = 56f; val topPad = 12f; val rightPad = 12f
            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - bottomPad - topPad
            val yFor = { v: Float -> topPad + plotH - plotH * ((v - minV) / range) }
            val baselineY = yFor(0f)
            val axisPaint = android.graphics.Paint().apply { color = labelArgb; textSize = 26f; isAntiAlias = true }
            val centerPaint = android.graphics.Paint().apply { color = labelArgb; textSize = 26f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
            val steps = 5
            for (s in 0..steps) {
                val v = minV + range * s / steps
                val y = yFor(v)
                drawLine(gridColor, Offset(leftPad, y), Offset(leftPad + plotW, y), 1f)
                drawContext.canvas.nativeCanvas.drawText(formatAxis(v), 6f, y + 9f, axisPaint)
            }
            drawLine(onSurface, Offset(leftPad, topPad), Offset(leftPad, topPad + plotH), 2f)
            // Baseline (value 0): bottom edge for all-positive data, mid-plot when negatives exist.
            drawLine(onSurface, Offset(leftPad, baselineY), Offset(leftPad + plotW, baselineY), 2f)
            val catCount = chart.categories.size.coerceAtLeast(1)
            when (chart.type) {
                ChartType.LINE -> {
                    val stepX = if (catCount > 1) plotW / (catCount - 1) else plotW
                    chart.series.forEachIndexed { si, ser ->
                        val col = chartPalette[si % chartPalette.size]
                        var prev: Offset? = null
                        for (ci in 0 until catCount) {
                            val v = ser.values.getOrNull(ci) ?: 0f
                            val p = Offset(leftPad + stepX * ci, yFor(v))
                            prev?.let { drawLine(col, it, p, 4f) }
                            drawCircle(col, 5f, p)
                            prev = p
                        }
                    }
                    for (ci in 0 until catCount) {
                        val x = leftPad + (if (catCount > 1) plotW / (catCount - 1) else plotW) * ci
                        drawContext.canvas.nativeCanvas.drawText(
                            chart.categories.getOrElse(ci) { "" },
                            x,
                            topPad + plotH + 34f,
                            centerPaint)
                    }
                }
                ChartType.SCATTER -> {
                    val stepX = if (catCount > 1) plotW / (catCount - 1) else plotW
                    chart.series.forEachIndexed { si, ser ->
                        val col = chartPalette[si % chartPalette.size]
                        for (ci in 0 until catCount) {
                            val v = ser.values.getOrNull(ci) ?: 0f
                            drawCircle(col, 7f, Offset(leftPad + stepX * ci, yFor(v)))
                        }
                    }
                    for (ci in 0 until catCount) {
                        val x = leftPad + (if (catCount > 1) plotW / (catCount - 1) else plotW) * ci
                        drawContext.canvas.nativeCanvas.drawText(
                            chart.categories.getOrElse(ci) { "" },
                            x,
                            topPad + plotH + 34f,
                            centerPaint)
                    }
                }
                ChartType.PIE, ChartType.DONUT -> {
                    val values = chart.series.firstOrNull()?.values ?: emptyList()
                    val total = values.sum().coerceAtLeast(0.0001f)
                    val d = minOf(plotW, plotH)
                    val topLeft = Offset(leftPad + (plotW - d) / 2, topPad + (plotH - d) / 2)
                    var startAngle = -90f
                    values.forEachIndexed { i, v ->
                        val sweep = 360f * (v / total)
                        drawArc(chartPalette[i % chartPalette.size], startAngle, sweep, true, topLeft, Size(d, d))
                        startAngle += sweep
                    }
                    if (chart.type == ChartType.DONUT) {
                        val hole = d * 0.5f
                        drawArc(
                            surfaceColor,
                            0f,
                            360f,
                            true,
                            Offset(topLeft.x + (d - hole) / 2, topLeft.y + (d - hole) / 2),
                            Size(hole, hole))
                    }
                }
                ChartType.STACKED_BAR -> {
                    val groupW = plotW / catCount
                    val pad = groupW * 0.2f
                    val barW = groupW - 2 * pad
                    val stackMax = (0 until catCount).maxOfOrNull { ci -> chart.series.sumOf { (it.values.getOrNull(ci) ?: 0f).toDouble() }.toFloat() }?.coerceAtLeast(1f) ?: 1f
                    for (ci in 0 until catCount) {
                        var yCursor = topPad + plotH
                        chart.series.forEachIndexed { si, ser ->
                            val v = ser.values.getOrNull(ci) ?: 0f
                            val h = plotH * (v / stackMax)
                            drawRect(
                                chartPalette[si % chartPalette.size],
                                Offset(leftPad + groupW * ci + pad, yCursor - h),
                                Size(barW, h))
                            yCursor -= h
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            chart.categories.getOrElse(ci) { "" },
                            leftPad + groupW * ci + groupW / 2,
                            topPad + plotH + 34f,
                            centerPaint)
                    }
                }
                else -> { // BAR / AREA -> grouped bars
                    val serCount = chart.series.size.coerceAtLeast(1)
                    val groupW = plotW / catCount
                    val pad = groupW * 0.15f
                    val barW = (groupW - 2 * pad) / serCount
                    for (ci in 0 until catCount) {
                        val gx = leftPad + groupW * ci + pad
                        for (si in 0 until serCount) {
                            val v = chart.series[si].values.getOrNull(ci) ?: 0f
                            val yv = yFor(v)
                            val top = minOf(yv, baselineY)
                            val h = kotlin.math.abs(yv - baselineY)
                            drawRect(
                                chartPalette[si % chartPalette.size],
                                Offset(gx + barW * si, top),
                                Size(barW * 0.92f, h))
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            chart.categories.getOrElse(ci) { "" },
                            leftPad + groupW * ci + groupW / 2,
                            topPad + plotH + 34f,
                            centerPaint)
                    }
                }
            }
        }
    }
}
