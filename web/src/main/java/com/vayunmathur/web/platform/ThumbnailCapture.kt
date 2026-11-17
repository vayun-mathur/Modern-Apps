package com.vayunmathur.web.platform

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.view.View
import kotlin.math.max

/** Long edge of a stored thumbnail, in pixels. */
const val THUMB_MAX_DIM = 512

/**
 * Draws [view] into a downscaled bitmap, or null if it has never been laid out.
 *
 * PixelCopy is wrong here twice over: it reads the window's surface, so it would capture the
 * Compose chrome painted over the WebView rather than the page, and it only works on views
 * attached to a window, while the pool holds detached ones. `getDrawingCache` is deprecated
 * and allocates at full resolution on the main thread.
 *
 * Must run on the main thread — `View.draw` is not thread safe. Draws straight at the target
 * scale so there is no full-size intermediate to resize afterwards.
 */
fun captureThumb(view: View): Bitmap? {
    val width = view.width
    val height = view.height
    if (width <= 0 || height <= 0) return null
    val scale = (THUMB_MAX_DIM.toFloat() / max(width, height)).coerceAtMost(1f)
    return runCatching {
        val bitmap = createBitmap(
            max(1, (width * scale).toInt()),
            max(1, (height * scale).toInt()),
        )
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        view.draw(canvas)
        bitmap
    }.getOrNull()
}
