package com.vayunmathur.camera.platform.camera2

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.PreviewTransform

/**
 * Camera2 migration Phase 0: glue contract between a [TextureView]'s
 * [SurfaceTexture] lifecycle and the session layer.
 *
 * The bridge owns the [Surface] created from the texture and translates the
 * view callbacks into [PreviewSurfaceEvent]s. It deliberately has no session
 * binding yet: consumers map events to [SessionStateMachine] inputs
 * (available/resized → reconfigure, destroyed → teardown) in a later phase.
 *
 * Ownership: the [TextureView] owns the [SurfaceTexture]; this bridge owns the
 * [Surface] it wraps it in and releases that [Surface] on destroy. The texture
 * itself is never released here (returning `true` from the destroyed callback
 * lets the view release it).
 */
sealed interface PreviewSurfaceEvent {
    data class Available(val surface: Surface, val width: Int, val height: Int) : PreviewSurfaceEvent
    data class SizeChanged(val surface: Surface, val width: Int, val height: Int) : PreviewSurfaceEvent

    /** [surface] is already released by the bridge; consumers must drop every reference. */
    data class Destroyed(val surface: Surface) : PreviewSurfaceEvent
}

/** Immutable view of what the bridge currently holds. */
data class PreviewSurfaceState(
    val surface: Surface? = null,
    val width: Int = 0,
    val height: Int = 0,
) {
    val isAvailable: Boolean get() = surface != null
}

class PreviewSurfaceBridge(
    private val onEvent: (PreviewSurfaceEvent) -> Unit,
) : TextureView.SurfaceTextureListener {

    var state: PreviewSurfaceState = PreviewSurfaceState()
        private set

    /** The current preview surface, or null between destroy and the next available. */
    val surface: Surface? get() = state.surface

    private var texture: SurfaceTexture? = null

    /** View this bridge is attached to; the transform target. */
    private var view: TextureView? = null

    /** Stream size pinned as the texture buffer size, or null before selection. */
    private var streamSize: FrameSize? = null
    private var sensorOrientationDegrees: Int = 0
    private var lensFacing: LensFacing? = null

    /** Last transform applied, for geometry breadcrumbs / tests of the wiring. */
    var lastTransform: PreviewTransform.Affine? = null
        private set

    /** Display rotation (0/90/180/270) of the attached view, or 0 before attach. */
    val displayRotationDegrees: Int
        get() = (view?.display?.rotation ?: 0) * 90

    /**
     * Sets the buffer size the texture produces. Constrained high-speed
     * sessions reject any output whose size is outside the HFR list, so
     * the slo-mo holder pins the preview texture to the selection size
     * before the session configures; regular sessions leave the view
     * size alone (the HAL scaler handles it).
     */
    fun setDefaultBufferSize(width: Int, height: Int) {
        texture?.setDefaultBufferSize(width, height)
    }

    /**
     * Pins the texture buffer to the session's selected [stream] size and
     * applies the centre-crop FILL transform for it. Must be called
     * before the session configures (buffer size is read at configure
     * time; pinning afterwards does not resize the HAL stream) and again
     * whenever the view size, stream size or display rotation changes.
     * Without this the TextureView stretches the buffer to the view.
     */
    fun setPreviewStream(
        stream: FrameSize,
        sensorOrientationDegrees: Int,
        lensFacing: LensFacing?,
    ) {
        val sizeChanged = streamSize != stream
        streamSize = stream
        this.sensorOrientationDegrees = sensorOrientationDegrees
        this.lensFacing = lensFacing
        if (sizeChanged) setDefaultBufferSize(stream.width, stream.height)
        applyPreviewTransform()
    }

    /**
     * Recomputes and installs the TextureView transform from the pinned
     * stream size, the view's current layout size and its display
     * rotation. No-op until both a stream and a laid-out view exist.
     */
    fun applyPreviewTransform() {
        val target = view ?: return
        // View mutation belongs on the UI thread; session callbacks
        // (onStreaming) arrive on the camera executor.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            target.post { applyPreviewTransform() }
            return
        }
        val stream = streamSize ?: return
        if (target.width <= 0 || target.height <= 0) return
        val displayRotationDegrees = this.displayRotationDegrees
        val affine = PreviewTransform.compute(
            streamWidth = stream.width,
            streamHeight = stream.height,
            viewWidth = target.width,
            viewHeight = target.height,
            sensorOrientationDegrees = sensorOrientationDegrees,
            displayRotationDegrees = displayRotationDegrees,
            facing = lensFacing,
        ) ?: return
        lastTransform = affine
        // TextureView.setTransform matrix: a view-pixel placement over
        // content TextureView already stretched onto the view rect (HWUI
        // concatenates it outside the buffer->view texture matrix), NOT
        // a view->texture sampling map — feeding the inverse fill scale
        // here shrank the preview into the top-left of the box, leaving
        // the right/bottom strips undrawn. NO rotation either (the
        // stream's own texture transform already applied the sensor
        // quarter-turn). See PreviewTransform.computeTextureAffine.
        val textureAffine = PreviewTransform.computeTextureAffine(
            streamWidth = stream.width,
            streamHeight = stream.height,
            viewWidth = target.width,
            viewHeight = target.height,
            sensorOrientationDegrees = sensorOrientationDegrees,
            displayRotationDegrees = displayRotationDegrees,
            facing = lensFacing,
        ) ?: return
        target.setTransform(Matrix().apply { setValues(textureAffine.toMatrixValues()) })
        com.vayunmathur.library.log.Log.status(
            "Camera2Preview",
            "preview geometry view=${target.width}x${target.height} " +
                "stream=${stream.width}x${stream.height} sensor=$sensorOrientationDegrees " +
                "display=$displayRotationDegrees rel=${affine.rotationDegrees} " +
                "textureRel=${textureAffine.rotationDegrees} " +
                "textureK=${textureAffine.a}x${textureAffine.d} " +
                "textureT=${textureAffine.tx}x${textureAffine.ty} " +
                "displayed=${affine.displayedWidth}x${affine.displayedHeight} " +
                "scale=${affine.scale}",
        )
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        this.texture = texture
        val created = Surface(texture)
        state = PreviewSurfaceState(created, width, height)
        onEvent(PreviewSurfaceEvent.Available(created, width, height))
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        val current = state.surface ?: return
        state = state.copy(width = width, height = height)
        // View geometry changed (rotation / window resize): re-derive the
        // fill transform for the new size even if the reopen below keeps
        // the same stream size.
        applyPreviewTransform()
        onEvent(PreviewSurfaceEvent.SizeChanged(current, width, height))
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        this.texture = null
        // The pinned buffer size was applied to the texture that just
        // died; a new texture (resume re-attach) starts unpinned. Forget
        // the pin so the next setPreviewStream re-applies
        // setDefaultBufferSize to it even when the stream size is
        // unchanged — otherwise the resume configure runs against the
        // new texture's default buffer size and fails once, while a
        // fresh bridge (cold start / manual Retry) pins correctly.
        streamSize = null
        val current = state.surface
        state = PreviewSurfaceState()
        if (current != null) {
            onEvent(PreviewSurfaceEvent.Destroyed(current))
            current.release()
        }
        // True: the TextureView may release the SurfaceTexture.
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        // Per-frame callback: intentionally not forwarded — consumers poll [state].
    }

    /** Installs this bridge on [view] (or detaches when [view] is re-used elsewhere). */
    fun attachTo(view: TextureView) {
        this.view = view
        view.surfaceTextureListener = this
        // Layout (window resize / rotation) can change the view size
        // without a surface-size callback reaching us first.
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewTransform()
        }
        applyPreviewTransform()
        // A view that already has a texture (re-attach after a config change)
        // never re-fires the available callback; synthesise it.
        val texture = view.surfaceTexture
        if (view.isAvailable && texture != null && state.surface == null) {
            onSurfaceTextureAvailable(texture, view.width, view.height)
        }
    }

    fun detachFrom(view: TextureView) {
        if (this.view === view) this.view = null
        if (view.surfaceTextureListener === this) {
            view.surfaceTextureListener = null
        }
    }
}
