package com.vayunmathur.library.map

/*
 * The frame half of [SurfaceMapRenderer] that runs on [MapRenderThread]: the camera snapshot
 * the main thread hands over, the one native frame drawn from it, and what comes back.
 *
 * Split out so SurfaceMapRenderer.kt stays under the FileLength limit, and so the boundary is
 * visible in one place: everything in [FrameSnapshot] is read on the main thread (the Compose
 * camera is main-thread state), everything in [drawFrame] runs on the render thread (the
 * native renderer is single-threaded), and [FrameResult] is all that crosses back.
 */

/** Camera + viewport + globe state for one frame, read on the main thread. */
internal data class FrameSnapshot(
    val position: CameraPosition,
    val widthDp: Float,
    val heightDp: Float,
    val globe: Boolean,
    val moon: Boolean,
) {
    /** True when this frame would draw anything different from [previous]. */
    fun hasChangedSince(previous: FrameSnapshot?): Boolean = previous == null || this != previous

    /**
     * Whether the Compose camera's tilt pivot must be refreshed from the resident heightmaps
     * after this frame: only under pitch on the flat path, the renderer's own gate
     * (`Renderer::render` resolves its pivot exactly when `pitch > 0` off-globe).
     */
    val wantsPivot: Boolean get() = position.pitch > 0.0 && !globe
}

/**
 * What one render-thread frame reports back to the main thread's loop.
 *
 * @property drawn the frame presented (false: no renderer, swapchain rebuilt, or no image).
 * @property pivotDp the refreshed tilt pivot, or NaN when not asked for.
 * @property regionPending a region mask is selected but its tiles have not landed yet.
 * @property nextDelayMillis [MapNative.nextFrameDelayMillis] after the frame.
 */
internal data class FrameResult(
    val drawn: Boolean,
    val pivotDp: Double,
    val regionPending: Boolean,
    val nextDelayMillis: Long,
) {
    companion object {
        /** No native renderer behind the frame (create failed, or a detach overtook it). */
        val NONE = FrameResult(drawn = false, pivotDp = Double.NaN, regionPending = false, nextDelayMillis = -1L)
    }
}

/**
 * Draw one frame on the render thread: the frame itself, then the tilt pivot and region-mask
 * retry that used to run on the main thread right after it, then the native "when next"
 * answer, so the main thread gets everything it schedules on in one hop.
 */
internal fun drawFrame(
    handle: Long,
    snapshot: FrameSnapshot,
    density: Float,
    frameTimeNanos: Long,
    pivotWanted: Boolean,
    region: NativeRegionMask,
): FrameResult {
    val position = snapshot.position
    val drawn = MapNative.render(
        handle,
        position.target.longitude.toFloat(),
        position.target.latitude.toFloat(),
        position.zoom.toFloat(),
        position.bearing.toFloat(),
        position.pitch.toFloat(),
        snapshot.widthDp,
        snapshot.heightDp,
        density,
        frameTimeNanos,
        snapshot.globe,
        snapshot.moon,
    )
    val pivot = if (pivotWanted) {
        MapNative.tiltPivotDp(
            handle,
            position.target.longitude.toFloat(),
            position.target.latitude.toFloat(),
            position.zoom.toFloat(),
        )
    } else {
        Double.NaN
    }
    if (region.pending) region.apply(handle)
    return FrameResult(drawn, pivot, region.pending, MapNative.nextFrameDelayMillis(handle))
}

/**
 * The Moon raster pair the host pushes for the lunar globe: converted LROC
 * color (RGBA8) + converted LDEM (RG8 packed uint16). Plain holder so the
 * renderer can remember and replay it across re-attach like every other
 * deferred state.
 */
data class MoonTextures(
    val colorRgba: ByteArray,
    val width: Int,
    val height: Int,
    val demRg: ByteArray,
    val demWidth: Int,
    val demHeight: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MoonTextures) return false
        return width == other.width && height == other.height &&
            demWidth == other.demWidth && demHeight == other.demHeight &&
            colorRgba.contentEquals(other.colorRgba) && demRg.contentEquals(other.demRg)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + demWidth
        result = 31 * result + demHeight
        result = 31 * result + colorRgba.contentHashCode()
        result = 31 * result + demRg.contentHashCode()
        return result
    }
}
