package com.vayunmathur.calculator.util

/**
 * The UI contract between [CalculatorViewModel] and the screens.
 *
 * Screens take a state value plus an actions interface rather than the ViewModel itself,
 * so they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, and the ViewModel implements these interfaces.
 */

/**
 * One evaluated expression, kept for the history sheet. [result] is the pretty
 * display form (thin space, unit symbols like `°C`/`µm`, localised dates);
 * [resultToken] is the same value in the parser's ASCII token form, which is
 * what tapping the entry seeds the input with — the display form does not
 * re-parse. Defaults to [result] so plain numeric entries need no second form.
 */
data class HistoryEntry(val expression: String, val result: String, val resultToken: String = result)

/** How far the graph can be zoomed out and in, in pixels per unit. */
private const val MIN_GRAPH_SCALE = 2.0
private const val MAX_GRAPH_SCALE = 400000.0

/** The visible window in graph units. */
data class GraphBounds(
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
)

/**
 * Where the graph is looking: centre in graph units, and pixels per unit.
 *
 * Owns the screen<->graph transform so drawing, tap handling and analysis all share one
 * definition of it. Kept free of Compose types so it stays a plain value type.
 */
data class GraphViewport(
    val centerX: Double = 0.0,
    val centerY: Double = 0.0,
    val scale: Double = 60.0,
) {
    fun graphX(screenX: Float, widthPx: Float): Double = centerX + (screenX - widthPx / 2) / scale

    fun graphY(screenY: Float, heightPx: Float): Double = centerY + (heightPx / 2 - screenY) / scale

    fun screenX(graphX: Double, widthPx: Float): Float = ((graphX - centerX) * scale + widthPx / 2).toFloat()

    fun screenY(graphY: Double, heightPx: Float): Float = (heightPx / 2 - (graphY - centerY) * scale).toFloat()

    fun bounds(widthPx: Float, heightPx: Float): GraphBounds = GraphBounds(
        xMin = graphX(0f, widthPx),
        xMax = graphX(widthPx, widthPx),
        yMin = graphY(heightPx, heightPx),
        yMax = graphY(0f, heightPx),
    )

    /**
     * The viewport after a transform gesture: zoom about the centroid, so the graph point
     * under the fingers stays under them, then pan at the new scale.
     */
    fun transformed(
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
        widthPx: Float,
        heightPx: Float,
    ): GraphViewport {
        val newScale = (scale * zoom).coerceIn(MIN_GRAPH_SCALE, MAX_GRAPH_SCALE)
        // Reused only for its centre->centroid offset at the new scale.
        val atNewScale = GraphViewport(scale = newScale)
        return GraphViewport(
            centerX = graphX(centroidX, widthPx) - atNewScale.graphX(centroidX, widthPx) - panX / newScale,
            // Screen Y grows downward, so the pan is added rather than subtracted.
            centerY = graphY(centroidY, heightPx) - atNewScale.graphY(centroidY, heightPx) + panY / newScale,
            scale = newScale,
        )
    }
}

/** Everything the keypad screen draws. */
data class CalculatorUiState(
    val input: String = "",
    val preview: String = "",
    val memory: Double = 0.0,
    val angleMode: AngleMode = AngleMode.RADIANS,
    val history: List<HistoryEntry> = emptyList(),
    /** Units compatible with the current result's dimension; empty when it's dimensionless. */
    val unitOptions: List<UnitDef> = emptyList(),
    /** The unit the result is currently shown in, chosen from [unitOptions]. */
    val selectedUnit: UnitDef? = null,
    /** Every category the unit picker can insert from. */
    val unitCategories: List<UnitCategory> = emptyList(),
)

/** Everything the graph screen draws. */
data class GraphUiState(
    val functions: List<GraphFunction> = emptyList(),
    val markers: List<GraphMarker> = emptyList(),
    val angleMode: AngleMode = AngleMode.RADIANS,
    val viewport: GraphViewport = GraphViewport(),
)

/**
 * Keypad callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface CalculatorActions {
    fun append(text: String) {}
    /** Insert an absolute date/datetime (epoch seconds) chosen from a picker. */
    fun insertInstant(epochSeconds: Long) {}
    fun clear() {}
    fun backspace() {}
    fun evaluate() {}
    fun toggleAngleMode() {}
    fun memoryClear() {}
    fun memoryRecall() {}
    fun memoryAdd() {}
    fun memorySubtract() {}
    fun useHistory(entry: HistoryEntry) {}

    /** Show the result in the compatible unit identified by [token]. */
    fun selectOutputUnit(token: String) {}

    companion object {
        val Noop: CalculatorActions = object : CalculatorActions {}
    }
}

/** Graph callbacks. Same no-op-default arrangement as [CalculatorActions]. */
interface GraphActions {
    fun toggleAngleMode() {}
    fun addFunction() {}
    fun updateFunction(id: Long, text: String) {}
    fun toggleFunction(id: Long) {}
    fun togglePolar(id: Long) {}
    fun removeFunction(id: Long) {}

    /** Report the canvas size so tap handling can convert pixels to graph units. */
    fun setViewSize(widthPx: Float, heightPx: Float) {}

    /** Pan/zoom result from a transform gesture. */
    fun setViewport(viewport: GraphViewport) {}

    /** Reveal or dismiss the notable point nearest [at], within [radius] graph units. */
    fun tapGraph(at: GraphPoint, radius: Double) {}

    companion object {
        val Noop: GraphActions = object : GraphActions {}
    }
}

/** Everything the unit-converter screen draws. */
data class UnitConverterUiState(
    val categories: List<UnitCategory> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val fromToken: String = "",
    val toToken: String = "",
    val inputText: String = "",
    val outputText: String = "",
    /** True while the live currency rates are being fetched (the Currency tab shows a spinner). */
    val currencyLoading: Boolean = false,
    /** Non-null when fetching currency rates failed; the Currency tab shows this with a retry. */
    val currencyError: String? = null,
    /** True once the selected category is the live Currency tab. */
    val isCurrencyCategory: Boolean = false,
)

/** Unit-converter callbacks. Same no-op-default arrangement as [CalculatorActions]. */
interface UnitConverterActions {
    fun selectCategory(index: Int) {}
    fun setFrom(token: String) {}
    fun setTo(token: String) {}
    fun setConverterInput(text: String) {}
    fun swapUnits() {}
    /** Re-fetch live currency exchange rates after a failure. */
    fun retryCurrency() {}

    companion object {
        val Noop: UnitConverterActions = object : UnitConverterActions {}
    }
}
