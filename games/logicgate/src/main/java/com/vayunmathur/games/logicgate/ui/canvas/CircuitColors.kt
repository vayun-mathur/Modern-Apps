package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.ColorScheme

/**
 * The canvas palette, derived from the theme once per scheme. Every colour that marks state
 * (wires, buses, selection, pass/fail) is checked against the board background and nudged
 * until it reaches 3:1, so highlights stay visible whatever the scheme's lightness. Signals are
 * never told apart by colour alone: GatePainter also varies stroke, dashing and pin fill.
 */
@Immutable
data class CircuitColors(
    val background: Color,
    val grid: Color,
    val gateFill: Color,
    val gateStroke: Color,
    val label: Color,
    val muted: Color,
    val wireOff: Color,
    val wireOn: Color,
    val bus4: Color,
    val bus8: Color,
    val selected: Color,
    val error: Color,
    val ok: Color,
    val ioFill: Color,
) {
    fun forWidth(width: Int): Color = when (width) {
        1 -> wireOff
        4 -> bus4
        else -> bus8
    }

    /** Palette factories. */
    companion object {
        fun from(scheme: ColorScheme): CircuitColors {
            val bg = scheme.background
            fun visible(c: Color) = Contrast.ensure(c, bg)
            return CircuitColors(
                background = bg,
                grid = scheme.outline.copy(alpha = 0.5f),
                gateFill = scheme.surfaceContainerHigh,
                gateStroke = visible(scheme.onSurfaceVariant),
                label = scheme.onSurface,
                muted = scheme.onSurfaceVariant,
                wireOff = visible(scheme.outline),
                wireOn = visible(scheme.primary),
                bus4 = visible(scheme.tertiary),
                // No theme role means "8-bit bus", "selected" or "passed"; keep their familiar
                // hues and let the background decide how light or dark they are drawn.
                bus8 = visible(BUS8_HUE),
                selected = visible(SELECTED_HUE),
                error = visible(scheme.error),
                ok = visible(OK_HUE),
                ioFill = scheme.secondaryContainer,
            )
        }

        private val BUS8_HUE = Color(0xFF7FA8FF)
        private val SELECTED_HUE = Color(0xFFFFE082)
        private val OK_HUE = Color(0xFF81C784)
    }
}
