package com.vayunmathur.calculator.platform

import com.vayunmathur.calculator.domain.*

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

/** One plotted curve on the graph screen — Cartesian `y = f(x)` or polar `r = f(θ)`. */
data class GraphFunction(
    val id: Long,
    val text: String = "",
    val color: Color,
    val enabled: Boolean = true,
    val polar: Boolean = false,
)

/**
 * A notable point the user revealed by touching near it. Held as the feature itself rather
 * than as pre-rendered text so the UI can colour and localise the label.
 */
data class GraphMarker(
    val point: GraphPoint,
    val kind: FeatureKind,
    /** The curve it belongs to, or the two curves that cross there. */
    val curveIds: List<Long>,
)

/**
 * Holds all calculator state at the Activity scope so it survives switching between the
 * Calculator and Graph tabs (which reset the nav back stack).
 *
 * The per-tab state lives in [CalculatorTab], [GraphTab] and [UnitConverterTab]; this class
 * only owns the shared trig mode and delegates each actions interface to its tab, which is
 * what keeps it under detekt's function cap.
 */
class CalculatorViewModel(
    application: Application,
    private val calculator: CalculatorTab = CalculatorTab(),
    private val graph: GraphTab = GraphTab(),
    private val converter: UnitConverterTab = UnitConverterTab(application),
) : AndroidViewModel(application),
    CalculatorActions by calculatorActionAdapter(calculator),
    GraphActions by graphActionAdapter(graph),
    UnitConverterActions by converterActionAdapter(converter) {

    /**
     * Java-visible single-arg constructor for AndroidViewModelFactory: it looks up
     * `CalculatorViewModel(Application)` via reflection, which Kotlin's default-argument
     * synthetic constructor does not expose.
     */
    constructor(application: Application) : this(
        application,
        CalculatorTab(),
        GraphTab(),
        UnitConverterTab(application),
    )

    // ---- Shared ----
    var angleMode by mutableStateOf(AngleMode.RADIANS)
        private set

    /**
     * Snapshot of everything the keypad screen draws.
     */
    val calculatorUiState: CalculatorUiState
        get() = calculator.uiState.copy(angleMode = angleMode)

    /** Snapshot of everything the graph screen draws. */
    val graphUiState: GraphUiState
        get() = graph.uiState.copy(angleMode = angleMode)

    val unitConverterUiState: UnitConverterUiState
        get() = converter.uiState

    init {
        converter.start(viewModelScope)
    }

    /**
     * Flip the trig mode shared by the keypad and graph tabs, refreshing the live preview.
     * This resolves the [CalculatorActions.toggleAngleMode]/[GraphActions.toggleAngleMode]
     * delegation conflict.
     */
    override fun toggleAngleMode() {
        angleMode = if (angleMode == AngleMode.RADIANS) AngleMode.DEGREES else AngleMode.RADIANS
        calculator.angleMode = angleMode
        graph.angleMode = angleMode
        calculator.refresh()
    }
}
