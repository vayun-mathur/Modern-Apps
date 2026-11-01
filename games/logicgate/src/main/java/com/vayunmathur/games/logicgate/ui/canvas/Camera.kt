package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * Maps world units (roughly dp at 1× zoom) to screen pixels: `screen = world * scale + offset`.
 * [scale] already includes display density.
 */
data class Camera(val offset: Offset = Offset.Zero, val scale: Float = 1f) {

    fun toScreen(world: Offset): Offset = world * scale + offset

    fun toWorld(screen: Offset): Offset = (screen - offset) / scale

    fun panBy(delta: Offset): Camera = copy(offset = offset + delta)

    /** Zooms by [factor] keeping the world point under [pivot] fixed on screen. */
    fun zoomAbout(pivot: Offset, factor: Float, minScale: Float, maxScale: Float): Camera {
        val next = (scale * factor).coerceIn(minScale, maxScale)
        val applied = next / scale
        return Camera(offset = pivot - (pivot - offset) * applied, scale = next)
    }

    /** Camera factories. */
    companion object {
        /** Frames [bounds] in a [viewport] with [padding] pixels to spare on every side. */
        fun fit(bounds: Rect, viewport: Size, padding: Float, minScale: Float, maxScale: Float): Camera {
            val w = (viewport.width - 2 * padding).coerceAtLeast(1f)
            val h = (viewport.height - 2 * padding).coerceAtLeast(1f)
            val scale = minOf(w / bounds.width.coerceAtLeast(1f), h / bounds.height.coerceAtLeast(1f))
                .coerceIn(minScale, maxScale)
            val center = Offset(viewport.width / 2f, viewport.height / 2f)
            return Camera(offset = center - bounds.center * scale, scale = scale)
        }
    }
}
