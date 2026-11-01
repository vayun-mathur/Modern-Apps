package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.ColorScheme

/** The canvas palette, derived from the theme once per scheme. */
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
        fun from(scheme: ColorScheme) = CircuitColors(
            background = scheme.background,
            grid = scheme.outline.copy(alpha = 0.5f),
            gateFill = scheme.surfaceContainerHigh,
            gateStroke = scheme.onSurfaceVariant,
            label = scheme.onSurface,
            muted = scheme.onSurfaceVariant,
            wireOff = Color(0xFF55657A),
            wireOn = scheme.primary,
            bus4 = scheme.tertiary,
            bus8 = Color(0xFF7FA8FF),
            selected = Color(0xFFFFE082),
            error = scheme.error,
            ok = Color(0xFF81C784),
            ioFill = scheme.secondaryContainer,
        )
    }
}
