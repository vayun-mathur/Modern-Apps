package com.vayunmathur.camera.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import com.vayunmathur.library.log.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.graphics.scale
import com.vayunmathur.library.ml.SelfieSegmenter

private const val TEMPORAL_WEIGHT = 0.35f
// Throttle segmentation to avoid running more than ~15 fps even if analysis
// delivers faster. KEEP_ONLY_LATEST already drops, but this skips the JNI work early.
private const val MIN_SEGMENT_INTERVAL_MS = 66L
// Preview analysis is now capped to 1024x768, but we still downscale to <=512 max side
// before feeding the 256x256 model to cut toBitmap -> orient allocations.
private const val MAX_PREVIEW_SIDE = 512

/** ImageAnalysis analyzer producing portrait bokeh masks from on-device segmentation. */
class BokehAnalyzer(
    context: Context,
    private val isFrontFacing: Boolean = false,
    private val onMaskGenerated: (Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    // Guard for the native handle and buffer fields – analyze() runs on bokehExecutor
    // while close() runs on the main composable's DisposableEffect. Without mutual
    // exclusion, close() destroys the net while the GPU is still reading the buffers it
    // owns  the SEGV_MTESERR tagged fault this used to hit in libncnn_android.so. The
    // runtime changed and ncnn has since left the tree entirely; the hazard did not.
    private val lock = Any()
    private var prevMask: FloatArray? = null
    private var blurTemp: FloatArray? = null
    private var blurDst: FloatArray? = null
    private var pixelBuffer: IntArray? = null
    private var lastSegmentMs: Long = 0L
    @Volatile private var closed = false
    // GPU-only: null-returning rather than throwing when the device has no Vulkan fp16
    // compute, in which case the preview simply stays sharp. A LinkageError from the
    // native entry point (library present but unlinkable) degrades the same way.
    private var segmenter: SelfieSegmenter? = try {
        SelfieSegmenter(context)
    } catch (e: LinkageError) {
        Log.error("BokehAnalyzer", "segmentation unavailable", e)
        null
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        try {
            if (closed) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastSegmentMs < MIN_SEGMENT_INTERVAL_MS) {
                return
            }
            lastSegmentMs = now

            val frame = prepareFrame(imageProxy) ?: return
            if (closed) {
                frame.recycle()
                return
            }

            // Synchronous forward pass; runs on dedicated bokehExecutor with KEEP_ONLY_LATEST.
            // Hold lock across the native call so close() blocks until the submitted command
            // buffer has been waited on, preventing the SEGV_MTESERR use-after-free seen in
            // tombstones.
            val result = segmentLocked(frame) ?: return
            frame.recycle()
            if (closed) return

            smoothAndEmit(result)
        } catch (e: IllegalStateException) {
            Log.error("BokehAnalyzer", "segmentation failed", e)
        } catch (e: IllegalArgumentException) {
            Log.error("BokehAnalyzer", "segmentation failed", e)
        } catch (e: NullPointerException) {
            // A frame with no decodable bitmap (null platform return from
            // toBitmap) is a dropped frame, not a crash of the preview path.
            Log.error("BokehAnalyzer", "segmentation failed", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Copies, downscales and orients the frame for the segmenter. Returns null when there is
     * no mask this frame (throttled, closed, or GPU unavailable); recycles intermediates.
     */
    private fun prepareFrame(imageProxy: ImageProxy): Bitmap? {
        // Raw sensor-oriented buffer; capped to 1024x768 in portrait session,
        // so this is ~0.8MP not 12MP. Full-res final image kept in ImageCapture.
        var frame = imageProxy.toBitmap()
        if (closed) {
            frame.recycle()
            return null
        }
        // Immediate downscale to <=512 max side, filter=false to avoid bilinear cost.
        val downscaled = downscaleIfNeeded(frame, MAX_PREVIEW_SIDE)
        if (downscaled !== frame) {
            frame.recycle()
        }
        frame = downscaled

        // Orient to display + mirror for front camera, filter=false (was true = bilinear).
        val oriented = orientToDisplay(frame, imageProxy.imageInfo.rotationDegrees)
        if (oriented !== frame) {
            frame.recycle()
        }
        return oriented
    }

    /** Runs the segmenter under lock; null when closed or the GPU yields no mask. */
    private fun segmentLocked(frame: Bitmap): com.vayunmathur.library.ml.SegmentationMask? {
        return synchronized(lock) {
            if (closed) {
                frame.recycle()
                null
            } else {
                val seg = segmenter
                if (seg == null) {
                    frame.recycle()
                    null
                } else {
                    // Null on an unavailable GPU or a failed submit; either way there is no
                    // mask this frame and the preview keeps the last one.
                    seg.segment(frame) ?: run {
                        frame.recycle()
                        null
                    }
                }
            }
        }
    }

    /** Temporally smooths, blurs and emits the mask bitmap. */
    private fun smoothAndEmit(result: com.vayunmathur.library.ml.SegmentationMask) {
        synchronized(lock) {
            if (closed) return
            val w = result.width
            val h = result.height
            val current = result.mask // foreground prob [0,1], row-major

            // Temporal smoothing: in-place blend into current array when possible to avoid alloc.
            val prev = prevMask
            val smoothed = if (prev != null && prev.size == current.size) {
                for (i in current.indices) {
                    current[i] = current[i] * (1f - TEMPORAL_WEIGHT) + prev[i] * TEMPORAL_WEIGHT
                }
                current
            } else {
                current
            }
            prevMask = smoothed

            // Blur for soft edges – reuses temp/dst buffers across frames to avoid GC pressure.
            var temp = blurTemp
            if (temp == null || temp.size != w * h) {
                temp = FloatArray(w * h)
                blurTemp = temp
            }
            var dst = blurDst
            if (dst == null || dst.size != w * h) {
                dst = FloatArray(w * h)
                blurDst = dst
            }
            val blurred = blurMask(smoothed, w, h, temp, dst)

            // Reuse pixel buffer.
            var pixels = pixelBuffer
            if (pixels == null || pixels.size != w * h) {
                pixels = IntArray(w * h)
                pixelBuffer = pixels
            }
            onMaskGenerated(maskToBitmap(blurred, w, h, pixels))
        }
    }

    fun close() {
        val toClose: SelfieSegmenter?
        synchronized(lock) {
            if (closed) return
            closed = true
            toClose = segmenter
            segmenter = null
            prevMask = null
            blurTemp = null
            blurDst = null
            pixelBuffer = null
        }
        // Destroy outside lock after nulling handle, but close() itself was blocked until
        // any in-flight segment() finished (lock held across native call), so the GPU is
        // no longer reading the buffers this frees.
        try { toClose?.close() } catch (_: Exception) {}
    }

    private fun downscaleIfNeeded(src: Bitmap, maxSide: Int): Bitmap {
        val max = maxOf(src.width, src.height)
        if (max <= maxSide) return src
        val scale = maxSide.toFloat() / max
        val newW = (src.width * scale).toInt().coerceAtLeast(1)
        val newH = (src.height * scale).toInt().coerceAtLeast(1)
        // filter=false – nearest/cheap, avoids bilinear alloc cost
        return src.scale(newW, newH, false)
    }

    /**
     * Rotate the sensor-oriented frame into display orientation (and mirror it
     * horizontally for the front camera) so the produced mask aligns with the
     * preview, which CameraX renders with that same transform.
     * filter=false to avoid second full-res bilinear alloc.
     */
    private fun orientToDisplay(src: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0 && !isFrontFacing) return src
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        if (isFrontFacing) matrix.postScale(-1f, 1f) // preview mirrors the front lens
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, false)
    }
}
