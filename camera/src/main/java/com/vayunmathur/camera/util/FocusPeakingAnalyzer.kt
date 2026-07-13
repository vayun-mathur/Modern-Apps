package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * Focus-peaking overlay analyzer. Runs a cheap Sobel gradient over a downscaled copy of the Y
 * (luminance) plane and emits an ARGB overlay bitmap that highlights high-gradient (in-focus)
 * edges in a bright color; everything else is transparent. Swapped onto the analysis stream while
 * the manual focus control is open (reusing the analyzer-swap pattern the night burst uses), and
 * the result is rendered upright (rotated/mirrored to match the preview).
 */
class FocusPeakingAnalyzer(
    private val mirror: Boolean,
    private val onOverlay: (Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val srcW = imageProxy.width
            val srcH = imageProxy.height

            // Downscale to ~320px wide so the gradient pass stays cheap on the analysis thread.
            val step = maxOf(1, srcW / 320)
            val w = srcW / step
            val h = srcH / step
            if (w < 3 || h < 3) {
                imageProxy.close()
                return
            }

            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val luma = IntArray(w * h)
            for (y in 0 until h) {
                val srcRow = (y * step) * rowStride
                val dstRow = y * w
                for (x in 0 until w) {
                    val idx = srcRow + x * step
                    luma[dstRow + x] = if (idx < data.size) data[idx].toInt() and 0xFF else 0
                }
            }

            val pixels = IntArray(w * h) // fully transparent by default
            val highlight = Color.argb(255, 255, 40, 200) // bright magenta
            val threshold = 48
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val i = row + x
                    val tl = luma[i - w - 1]; val tm = luma[i - w]; val tr = luma[i - w + 1]
                    val ml = luma[i - 1]; val mr = luma[i + 1]
                    val bl = luma[i + w - 1]; val bm = luma[i + w]; val br = luma[i + w + 1]
                    val gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl)
                    val gy = (bl + 2 * bm + br) - (tl + 2 * tm + tr)
                    if (abs(gx) + abs(gy) > threshold) pixels[i] = highlight
                }
            }

            var overlay = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            val degrees = imageProxy.imageInfo.rotationDegrees
            if (degrees != 0 || mirror) {
                val m = Matrix().apply {
                    postRotate(degrees.toFloat())
                    if (mirror) postScale(-1f, 1f)
                }
                val rotated = Bitmap.createBitmap(overlay, 0, 0, w, h, m, false)
                if (rotated !== overlay) overlay.recycle()
                overlay = rotated
            }
            onOverlay(overlay)
        } catch (_: Exception) {
            // Peaking is best-effort; drop this frame on any decode/index error.
        } finally {
            imageProxy.close()
        }
    }
}
