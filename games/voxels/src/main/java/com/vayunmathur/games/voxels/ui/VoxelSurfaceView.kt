package com.vayunmathur.games.voxels.ui

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.vayunmathur.games.voxels.util.VoxelsNative
import com.vayunmathur.library.log.Log

/** SurfaceView that forwards its surface lifecycle to the Rust engine. */
class VoxelSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private var ready = false
    init { holder.addCallback(this) }
    // JNI boundary: native throws unchecked exceptions, all logged here.
    @Suppress("TooGenericExceptionCaught")
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!VoxelsNative.isAvailable) return
        // JNI boundary: native throws unchecked exceptions, all logged here.
        try {
            VoxelsNative.surfaceCreated(holder.surface)
            ready = true
        } catch (e: RuntimeException) {
            Log.error("VoxelSurface", "surfaceCreated failed", e)
        }
    }
    @Suppress("TooGenericExceptionCaught")
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!VoxelsNative.isAvailable || !ready) return
        try { VoxelsNative.surfaceChanged(width, height) } catch (e: RuntimeException) {
            Log.error("VoxelSurface", "surfaceChanged failed", e)
        }
    }
    @Suppress("TooGenericExceptionCaught")
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!VoxelsNative.isAvailable) return
        try {
            VoxelsNative.surfaceDestroyed()
            ready = false
        } catch (e: RuntimeException) {
            Log.error("VoxelSurface", "surfaceDestroyed failed", e)
        }
    }
}
