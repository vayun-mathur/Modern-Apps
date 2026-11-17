package com.vayunmathur.calculator.platform

import com.vayunmathur.calculator.domain.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlin.math.hypot

/**
 * The graph tab: plotted functions, viewport, and revealed markers.
 *
 * Extracted from [CalculatorViewModel] so the ViewModel stays under detekt's function cap;
 * the ViewModel delegates [GraphActions] to this and exposes [uiState].
 */
class GraphTab {
    /** The trig mode sampling uses; synced from the ViewModel whenever it changes. */
    var angleMode: AngleMode = AngleMode.RADIANS

    val functions = mutableStateListOf(
        GraphFunction(id = 0, text = "x", color = GraphTabColors[0]),
    )
    private var nextId = 1L

    fun addFunction() {
        val color = GraphTabColors[functions.size % GraphTabColors.size]
        functions.add(GraphFunction(id = nextId++, text = "", color = color))
    }

    fun updateFunction(id: Long, text: String) {
        dropMarkersFor(id)
        mutate(id) { it.copy(text = text) }
    }

    fun toggleFunction(id: Long) {
        dropMarkersFor(id)
        mutate(id) { it.copy(enabled = !it.enabled) }
    }

    fun togglePolar(id: Long) {
        dropMarkersFor(id)
        mutate(id) { it.copy(polar = !it.polar) }
    }

    fun removeFunction(id: Long) {
        functions.removeAll { it.id == id }
        if (functions.isEmpty()) addFunction()
        dropMarkersFor(id)
    }

    private inline fun mutate(id: Long, transform: (GraphFunction) -> GraphFunction) {
        val index = functions.indexOfFirst { it.id == id }
        if (index >= 0) functions[index] = transform(functions[index])
    }

    // ---- Viewport (hoisted here so it survives tab switches and analysis can read it) ----
    var centerX by mutableDoubleStateOf(0.0)
    var centerY by mutableDoubleStateOf(0.0)
    var scale by mutableDoubleStateOf(60.0) // pixels per unit
    var viewWidthPx by mutableFloatStateOf(0f)
    var viewHeightPx by mutableFloatStateOf(0f)

    /** Notable points the user has revealed by touching near them. */
    val markers = mutableStateListOf<GraphMarker>()

    /** Snapshot of everything the graph screen draws. */
    val uiState: GraphUiState
        get() = GraphUiState(
            functions = functions,
            markers = markers,
            angleMode = angleMode,
            viewport = GraphViewport(centerX, centerY, scale),
        )

    fun setViewSize(widthPx: Float, heightPx: Float) {
        viewWidthPx = widthPx
        viewHeightPx = heightPx
    }

    fun setViewport(viewport: GraphViewport) {
        centerX = viewport.centerX
        centerY = viewport.centerY
        scale = viewport.scale
    }

    /**
     * Handles a tap on the graph, [radius] being the touch slop in graph units. Touching a
     * marker removes it; touching anywhere else reveals the nearest notable point in range,
     * across every curve on screen — Cartesian, polar, and crossings between the two.
     */
    fun tapGraph(at: GraphPoint, radius: Double) {
        val hit = markers.minByOrNull { hypot(it.point.x - at.x, it.point.y - at.y) }
        if (hit != null && hypot(hit.point.x - at.x, hit.point.y - at.y) <= radius) {
            markers.remove(hit)
            return
        }
        val feature = GraphAnalysis
            .featuresNear(sampleCurves(viewWidthPx, viewHeightPx), at, radius, angleMode)
            .firstOrNull() ?: return
        markers.add(GraphMarker(feature.point, feature.kind, feature.curveIds))
    }

    /**
     * Samples every visible curve over the current viewport. Drawing and analysis share this
     * so a marker always lands exactly on the line that was drawn.
     */
    private fun sampleCurves(widthPx: Float, heightPx: Float): List<SampledCurve> {
        if (widthPx <= 0f || heightPx <= 0f) return emptyList()
        val (xMin, xMax, yMin, yMax) = GraphViewport(centerX, centerY, scale).bounds(widthPx, heightPx)
        return functions.filter { it.enabled && it.text.isNotBlank() }.mapNotNull { fn ->
            val expr = runCatching { Expression.parse(fn.text) }.getOrNull() ?: return@mapNotNull null
            GraphAnalysis.sample(fn.id, expr, fn.polar, angleMode, xMin, xMax, yMin, yMax, widthPx.toInt())
        }
    }

    private fun dropMarkersFor(id: Long) = markers.removeAll { id in it.curveIds }
}

/** Distinct, colour-blind-friendly curve colours, reused cyclically. */
internal val GraphTabColors = listOf(
    Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFF34A853),
    Color(0xFFFF9800), Color(0xFF9C27B0), Color(0xFF00BCD4),
)

/** Adapts [GraphTab] to the [GraphActions] interface for ViewModel delegation. */
internal fun graphActionAdapter(tab: GraphTab): GraphActions = object : GraphActions {
    override fun addFunction() = tab.addFunction()
    override fun updateFunction(id: Long, text: String) = tab.updateFunction(id, text)
    override fun toggleFunction(id: Long) = tab.toggleFunction(id)
    override fun togglePolar(id: Long) = tab.togglePolar(id)
    override fun removeFunction(id: Long) = tab.removeFunction(id)
    override fun setViewSize(widthPx: Float, heightPx: Float) = tab.setViewSize(widthPx, heightPx)
    override fun setViewport(viewport: GraphViewport) = tab.setViewport(viewport)
    override fun tapGraph(at: GraphPoint, radius: Double) = tab.tapGraph(at, radius)
}
