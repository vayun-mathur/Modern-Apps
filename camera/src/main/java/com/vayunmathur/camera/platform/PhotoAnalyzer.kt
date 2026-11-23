package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.os.SystemClock
import com.vayunmathur.library.log.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.vayunmathur.camera.domain.LumaPlaneMath
import com.vayunmathur.camera.domain.PlaneView
import com.vayunmathur.camera.platform.camera2.toBitmapFromPlanes

/**
 * Combined PHOTO-stream analyzer. The ImageAnalysis use case can only host one analyzer, so this
 * reads the Y (luminance) plane once and both (1) samples average brightness for night-mode
 * detection and (2) runs the ZXing QR decode.
 *
 * Runs on the ViewModel's dedicated analysis thread, not the main one: the decode plus the
 * Motion-Photo frame copy cost tens of ms per frame, and under STRATEGY_KEEP_ONLY_LATEST that
 * starved the analyzer of frames when it shared the main thread with preview rendering.
 *
 * When [onMotionFrame] is supplied it also emits a copy of each frame (bitmap + timestamp +
 * rotation) so the ViewModel can maintain a Motion-Photo ring buffer off this same stream.
 */
/**
 * Minimum gap between full ZXing decode attempts (ms). A held QR code still
 * resolves within a fraction of a second, while the analysis thread stops
 * re-binarizing megapixel frames dozens of times per second.
 */
internal const val QR_DECODE_INTERVAL_MS = 400L

/**
 * Minimum gap between Motion-Photo RGB frame copies (ms). The ring buffer is
 * sized for [CameraViewModel.MOTION_FRAMES_PER_SECOND] (8 fps) and recycles
 * anything above it, so converting faster only churns bitmaps that are
 * immediately thrown away.
 */
internal const val MOTION_FRAME_INTERVAL_MS = 125L

/** Combined luminance, QR and Motion-Photo frame analyzer for the photo stream. */
class PhotoAnalyzer(
    private val onLuminance: (Float) -> Unit,
    private val onQrDetected: (String) -> Unit,
    private val onMotionFrame: ((Bitmap, Long, Int) -> Unit)? = null
) {
    private val reader = MultiFormatReader().apply {
        // Restrict to 2D codes only. Without this, ZXing's default MultiFormatReader
        // also decodes 1D linear barcodes (EAN/UPC/Code-128/ITF) which yield purely
        // numeric strings. In landscape, horizontal scene edges mimic linear barcode
        // bars and produce spurious numeric "QR" detections.
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
            )
        )
    }

    // Last-run timestamps for the cadence-capped ops below. analyze() runs on
    // the single analysis executor, so plain fields are sufficient.
    private var lastDecodeAtMs = 0L
    private var lastMotionAtMs = 0L

    /**
     * Camera2 analysis-frame entry: the full consumer set (luma, QR,
     * Motion-Photo copies) fed from an [AnalysisFrame]'s planes. Luma
     * runs per frame from the Y plane in place; the two expensive ops
     * (bitmap conversion for the Motion-Photo ring, ZXing decode) run
     * at the same capped cadences as the legacy CameraX analyzer. The
     * frame's [AnalysisFrame.rotationDegrees] replaces
     * `imageInfo.rotationDegrees` for the Motion-Photo copies.
     */
    fun onAnalysisFrame(frame: com.vayunmathur.camera.platform.camera2.AnalysisFrame) {
        onYPlane(frame.yPlane)
        val nowMs = SystemClock.elapsedRealtime()
        if (onMotionFrame != null && nowMs - lastMotionAtMs >= MOTION_FRAME_INTERVAL_MS) {
            lastMotionAtMs = nowMs
            try {
                val bmp = frame.toBitmapFromPlanes()
                if (bmp != null) {
                    try {
                        onMotionFrame.invoke(bmp, frame.timestampNs, frame.rotationDegrees)
                    } catch (e: IllegalStateException) {
                        Log.error("PhotoAnalyzer", "onMotionFrame emit threw", e)
                    } catch (e: IllegalArgumentException) {
                        Log.error("PhotoAnalyzer", "onMotionFrame emit threw", e)
                    }
                }
            } catch (e: IllegalStateException) {
                Log.error("PhotoAnalyzer", "toBitmapFromPlanes threw", e)
            } catch (e: IllegalArgumentException) {
                Log.error("PhotoAnalyzer", "toBitmapFromPlanes threw", e)
            }
        }
        if (nowMs - lastDecodeAtMs >= QR_DECODE_INTERVAL_MS) {
            lastDecodeAtMs = nowMs
            val bytes = copyYPlaneTight(frame.yPlane, frame.width, frame.height)
            if (bytes != null) {
                decodeQrBytes(bytes, frame.width, frame.height)
            }
        }
    }

    /**
     * ZXing decode over a tightly-packed Y copy (dataWidth == width).
     * [PlanarYUVLuminanceSource] takes the backing array's stride as
     * its dataWidth; packing rows tight makes that the image width, so
     * the historical rowStride shear (see the legacy note) cannot
     * occur. Rotation is not applied: ZXing hunts finder patterns in
     * any orientation for the square 2D codes enabled here.
     */
    private fun decodeQrBytes(bytes: ByteArray, width: Int, height: Int) {
        val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        try {
            val result = reader.decodeWithState(bitmap)
            try {
                onQrDetected(result.text)
            } catch (e: IllegalStateException) {
                Log.error("PhotoAnalyzer", "onQrDetected threw", e)
            } catch (e: IllegalArgumentException) {
                Log.error("PhotoAnalyzer", "onQrDetected threw", e)
            }
        } catch (_: NotFoundException) {
            // expected – no QR in frame, NOT an error
        } catch (e: com.google.zxing.ChecksumException) {
            Log.error("PhotoAnalyzer", "ZXing decode threw", e)
        } catch (e: com.google.zxing.FormatException) {
            Log.error("PhotoAnalyzer", "ZXing decode threw", e)
        } finally {
            try {
                reader.reset()
            } catch (e: IllegalStateException) {
                Log.error("PhotoAnalyzer", "reader.reset() threw", e)
            }
        }
    }

    /**
     * Plane-based entry point shared by the CameraX analyzer below and the
     * Camera2 ImageReader analysis source: luma sampling only, read straight
     * from the plane buffer with no byte copy and no bitmap conversion. QR
     * and Motion-Photo copies deliberately have no plane entry point this
     * phase (see AnalysisSourcePolicy) and stay on the CameraX path.
     */
    fun onYPlane(plane: PlaneView) {
        // Luma via the Rust plane-stats work (StitchNative.lumaStats, with
        // the identical Kotlin fallback for non-direct/unavailable cases).
        val avg = StitchNative.lumaStats(plane)?.mean ?: return
        try {
            onLuminance(avg.toFloat())
        } catch (e: IllegalStateException) {
            Log.error("NightPreview", "PhotoAnalyzer onLuminance callback threw (was hidden)", e)
        } catch (e: IllegalArgumentException) {
            Log.error("NightPreview", "PhotoAnalyzer onLuminance callback threw (was hidden)", e)
        }
    }

}

/**
 * Copies a Y plane into a tightly packed `width * height` array
 * (rowStride == width), honouring the source row/pixel strides so HAL
 * row padding and semi-planar layouts never shear the image. Null when
 * the plane cannot describe the full frame.
 */
internal fun copyYPlaneTight(plane: PlaneView, width: Int, height: Int): ByteArray? {
    if (width <= 0 || height <= 0 || plane.rowStride <= 0 || plane.pixelStride <= 0) return null
    val buffer = plane.buffer.duplicate()
    val last = (height - 1).toLong() * plane.rowStride +
        (width - 1).toLong() * plane.pixelStride
    if (last >= buffer.limit()) return null
    val out = ByteArray(width * height)
    var offset = 0
    for (row in 0 until height) {
        val rowBase = row * plane.rowStride
        if (plane.pixelStride == 1) {
            buffer.position(rowBase)
            buffer.get(out, offset, width)
            offset += width
        } else {
            for (col in 0 until width) {
                out[offset++] = buffer.get(rowBase + col * plane.pixelStride)
            }
        }
    }
    return out
}
