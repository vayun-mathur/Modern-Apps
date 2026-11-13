package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight

/**
 * How big canvas labels are drawn. Labels are designed in world units (≈dp at 1×), so on their
 * own they shrink with the zoom and ignore the system font scale. Here a label is drawn at least
 * [MIN_LABEL_DP] × font scale on screen and grows (a little) with the font scale, but is left
 * out entirely once that would take more than [MAX_GROW] × its design size, where it would no
 * longer fit its gate: unreadable text is never drawn. [dpPerWorld] is the camera's on-screen dp
 * per world unit.
 */
class LabelSizing(private val dpPerWorld: Float, private val fontScale: Float) {

    /** The size a label designed at [base] world units is measured at. */
    fun measured(base: Float): Float = base * fontScale.coerceIn(1f, MAX_FONT_GROW)

    /** The world size to draw a [base] label at, or null to leave it out at this zoom. */
    fun drawn(base: Float): Float? {
        if (dpPerWorld <= 0f) return null
        val floor = MIN_LABEL_DP * fontScale / dpPerWorld
        return maxOf(measured(base), floor).takeIf { it <= base * MAX_GROW }
    }

    /** Sizing constants. */
    companion object {
        /** Smallest on-screen label size at font scale 1, in dp. */
        const val MIN_LABEL_DP = 11f

        /** How far past its design size a label may grow before it is hidden instead. */
        const val MAX_GROW = 1.6f

        /** How much of the font scale a label follows at any zoom (gates do not grow with it). */
        const val MAX_FONT_GROW = 1.3f

        /** 1 dp per world unit at font scale 1, for previews and tests. */
        val Default = LabelSizing(1f, 1f)
    }
}

/** A measured label and the factor it is drawn at; [width] and [height] are in world units. */
class ScaledLabel(val layout: TextLayoutResult, val factor: Float) {
    val width: Float get() = layout.size.width * factor
    val height: Float get() = layout.size.height * factor
}

/** [text] designed at [base] world units, sized for the current zoom, or null when hidden. */
fun DrawScope.label(ctx: PaintContext, text: String, base: Float, weight: FontWeight? = null): ScaledLabel? {
    val drawn = ctx.sizing.drawn(base) ?: return null
    val measured = ctx.sizing.measured(base)
    return ScaledLabel(ctx.text.measure(this, text, measured, weight), drawn / measured)
}

/** Draws [label] with its top-left corner at [topLeft]. */
fun DrawScope.drawLabel(label: ScaledLabel, color: Color, topLeft: Offset) {
    scale(label.factor, label.factor, pivot = topLeft) { drawText(label.layout, color, topLeft) }
}
