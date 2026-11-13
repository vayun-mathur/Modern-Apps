package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** WCAG contrast between canvas colours, and the nudge that makes a colour readable. */
object Contrast {
    /** Minimum contrast for graphics and UI parts against their background (WCAG 1.4.11). */
    const val GRAPHICS = 3f

    /** The WCAG contrast ratio of [a] against [b], from 1 (same) to 21 (black on white). */
    fun ratio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + OFFSET) / (minOf(la, lb) + OFFSET)
    }

    /**
     * [color] unchanged when it already reaches [min] against [background]; otherwise the
     * smallest blend towards black or white (whichever contrasts more with [background]) that
     * does, so the hue survives as far as it can.
     */
    fun ensure(color: Color, background: Color, min: Float = GRAPHICS): Color {
        if (ratio(color, background) >= min) return color
        val toward = if (ratio(Color.Black, background) > ratio(Color.White, background)) Color.Black else Color.White
        var lo = 0f
        var hi = 1f
        repeat(SEARCH_STEPS) {
            val mid = (lo + hi) / 2
            if (ratio(lerp(color, toward, mid), background) >= min) hi = mid else lo = mid
        }
        return lerp(color, toward, hi)
    }

    private const val OFFSET = 0.05f
    private const val SEARCH_STEPS = 20
}
