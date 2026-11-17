package com.vayunmathur.library.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector

internal class PanoramaSphereGLView(
    context: Context,
    crop: PanoramaCrop,
    initialYaw: Float?,
    private val cameraState: PanoramaCameraState?,
    loadTexture: (Int) -> android.graphics.Bitmap?,
) : GLSurfaceView(context) {

    // Tools-only constructors (lint ViewConstructor): the view is only ever
    // created programmatically with a crop/texture loader, so these exist for
    // layout tooling and refuse to render without them.
    constructor(context: Context) : this(
        context, PanoramaCrop.full(1, 1), null, null, { null },
    )

    constructor(context: Context, attrs: android.util.AttributeSet?) : this(
        context, PanoramaCrop.full(1, 1), null, null, { null },
    )

    constructor(
        context: Context,
        attrs: android.util.AttributeSet?,
        defStyleAttr: Int,
    ) : this(context, PanoramaCrop.full(1, 1), null, null, { null })

    private val renderer = SphereRenderer(crop, initialYaw, loadTexture)
    private val scaleDetector: ScaleGestureDetector

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Pinch out (scaleFactor > 1) zooms in → narrower FOV.
                renderer.fov = (renderer.fov / detector.scaleFactor).coerceIn(MIN_FOV, MAX_FOV)
                publishCamera()
                requestRender()
                return true
            }
        })
        publishCamera()
    }

    private fun publishCamera() {
        cameraState?.publish(renderer.yaw, renderer.pitch, renderer.fov)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    // Drag "grabs" the scene: dragging right/down brings the
                    // content that was to the left/above into view. Scale by FOV
                    // so the feel is consistent across zoom levels.
                    val speed = DRAG_SPEED * (renderer.fov / PanoramaCameraState.DEFAULT_FOV_DEG)
                    renderer.addYaw(dx * speed)
                    renderer.addPitch(dy * speed)
                    lastX = event.x; lastY = event.y
                    publishCamera()
                    requestRender()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) performClick()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun performClick(): Boolean {
        // Touch is consumed for drag/pinch camera control; a click has no
        // separate action, but accessibility services invoke it on tap.
        super.performClick()
        return true
    }

    companion object {
        // Radians of rotation per pixel of drag at the default FOV.
        private const val DRAG_SPEED = 0.0015f
        private const val MIN_FOV = 30f
        private const val MAX_FOV = 100f
    }
}
