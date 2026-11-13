package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density

/**
 * Canvas text measured once and re-used every frame, keyed by string, world size and weight.
 * A board shows far more distinct labels than the [TextMeasurer]'s own cache of 8 holds, so
 * without this every label was re-measured on every frame of a drag or pan.
 */
class TextCache(private val measurer: TextMeasurer) {
    private data class Key(val text: String, val sizeWorld: Float, val weight: FontWeight?)

    private val layouts = HashMap<Key, TextLayoutResult>()

    /** [text] at [sizeWorld] world units (the camera scale is applied by the caller's transform). */
    fun measure(density: Density, text: String, sizeWorld: Float, weight: FontWeight? = null): TextLayoutResult =
        layouts.getOrPut(Key(text, sizeWorld, weight)) {
            // Live bus values can produce many distinct strings over a session; stay bounded.
            if (layouts.size >= MAX_ENTRIES) layouts.clear()
            measurer.measure(text, TextStyle(fontSize = with(density) { sizeWorld.toSp() }, fontWeight = weight))
        }

    val size: Int get() = layouts.size

    private companion object {
        const val MAX_ENTRIES = 512
    }
}
