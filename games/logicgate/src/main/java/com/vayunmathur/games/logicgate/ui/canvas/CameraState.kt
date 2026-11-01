package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/** The editor's camera, hoisted so the top bar and chip tray can fit and place relative to it. */
@Stable
class CameraState(private val density: Float) {
    var camera by mutableStateOf(Camera(scale = density))
    var viewport by mutableStateOf(Size.Zero)

    /** The fit request last honoured, so returning to a level does not re-frame it. */
    var fittedToken = -1

    private val minScale get() = MIN_ZOOM * density
    private val maxScale get() = MAX_ZOOM * density

    fun pan(delta: Offset) {
        camera = camera.panBy(delta)
    }

    fun zoom(pivot: Offset, factor: Float) {
        camera = camera.zoomAbout(pivot, factor, minScale, maxScale)
    }

    /** Frames [bounds]; small circuits stop at a comfortable zoom instead of filling the screen. */
    fun fit(bounds: Rect?) {
        if (bounds == null || viewport.width <= 0f || viewport.height <= 0f) return
        camera = Camera.fit(bounds, viewport, FIT_PADDING * density, minScale, FIT_MAX_ZOOM * density)
    }

    fun centerWorld(): Offset = camera.toWorld(Offset(viewport.width / 2f, viewport.height / 2f))

    private companion object {
        const val MIN_ZOOM = 0.3f
        const val MAX_ZOOM = 3f
        const val FIT_MAX_ZOOM = 1.4f
        const val FIT_PADDING = 40f
    }
}
