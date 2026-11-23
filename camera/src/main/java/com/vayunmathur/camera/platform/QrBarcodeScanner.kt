package com.vayunmathur.camera.platform

import android.os.SystemClock
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.vayunmathur.camera.platform.camera2.AnalysisFrame
import com.vayunmathur.library.log.Log

/**
 * Minimum gap between decode attempts in QR mode (ms). Dedicated scanning
 * wants a held code to resolve fast, but binarizing near-megapixel luma
 * dozens of times a second would starve the single analysis executor;
 * frames arriving inside the gap are dropped by the source's drop gate
 * semantics (the scanner simply declines to decode them).
 */
internal const val QR_SCAN_INTERVAL_MS = 250L

/**
 * Formats decoded live in PHOTO mode (there is no separate QR mode):
 * the 2D family the old ambient scanner covered (QR above all) plus
 * the common 1D symbologies, stock-camera style. [PhotoAnalyzer]'s own
 * decode stays QR-only and is disabled where this scanner is fed, so
 * each frame is binarized once; the 250 ms throttle bounds the cost of
 * the wider set on the analysis executor.
 */
internal val QR_BARCODE_FORMATS: List<BarcodeFormat> = listOf(
    BarcodeFormat.QR_CODE,
    BarcodeFormat.AZTEC,
    BarcodeFormat.DATA_MATRIX,
    BarcodeFormat.PDF_417,
    BarcodeFormat.EAN_13,
    BarcodeFormat.EAN_8,
    BarcodeFormat.UPC_A,
    BarcodeFormat.UPC_E,
    BarcodeFormat.CODE_39,
    BarcodeFormat.CODE_93,
    BarcodeFormat.CODE_128,
    BarcodeFormat.ITF,
    BarcodeFormat.CODABAR,
)

/**
 * QR/barcode scanner running inside PHOTO mode, fed from the Camera2
 * analysis stream ([AnalysisFrame]).
 *
 * Decode path, cheapest first:
 * 1. The Y (luma) plane straight into ZXing's [PlanarYUVLuminanceSource]
 *    when the plane is array-backed with pixelStride 1 — no copy, no
 *    ARGB bitmap. The source's dataWidth is the plane's rowStride, so
 *    HAL row padding never shears the image.
 * 2. A tightly packed Y copy ([copyYPlaneTight]) for direct-buffer or
 *    strided planes (the common Camera2 case).
 * 3. Only when the frame reports a quarter-turn rotation and the upright
 *    decode missed: the packed crop transposed, so 1D barcodes (which,
 *    unlike QR finder patterns, are orientation-sensitive) still read
 *    when the phone is held sideways relative to the code.
 *
 * The frame's [AnalysisFrame.cropRect] bounds the decode region on every
 * path. No path converts to an ARGB bitmap: ZXing never needs colour.
 *
 * Threading: [onAnalysisFrame] is invoked through the analysis stream's
 * dispatcher, i.e. on the Camera2 analysis executor — never on the
 * capture/session thread and never on the main thread. Instances are
 * confined to that executor (the cadence fields are plain for that
 * reason); the ViewModel swaps the sink atomically per mode.
 */
class QrBarcodeScanner(
    private val onDetected: (text: String, format: BarcodeFormat) -> Unit,
) {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to QR_BARCODE_FORMATS,
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.ALSO_INVERTED to true,
            )
        )
    }

    /** Last decode attempt; internal so tests can rewind the cadence. */
    internal var lastDecodeAtMs = 0L

    /** Camera2 analysis-frame entry. See the class doc for threading. */
    fun onAnalysisFrame(frame: AnalysisFrame) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastDecodeAtMs < QR_SCAN_INTERVAL_MS) return
        lastDecodeAtMs = nowMs
        val result = decodeFrame(frame) ?: return
        try {
            onDetected(result.text, result.barcodeFormat)
        } catch (e: IllegalStateException) {
            Log.error("QrBarcodeScanner", "onDetected threw", e)
        } catch (e: IllegalArgumentException) {
            Log.error("QrBarcodeScanner", "onDetected threw", e)
        }
    }

    private fun decodeFrame(frame: AnalysisFrame): Result? {
        decodeDirect(frame)?.let { return it }
        val packed = copyYPlaneTight(frame.yPlane, frame.width, frame.height) ?: return null
        val crop = frame.cropRect
        decodePacked(packed, frame.width, frame.height, crop.left, crop.top, crop.width, crop.height)
            ?.let { return it }
        // 1D codes read along one axis only: retry quarter-turned frames
        // transposed. The crop is applied before transposing so the
        // rotated source needs no offset bookkeeping.
        val quarterTurn = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
        if (!quarterTurn) return null
        val cropped = cropPacked(packed, frame.width, crop) ?: return null
        val transposed = transpose(cropped.bytes, cropped.width, cropped.height)
        return decodePacked(transposed, cropped.height, cropped.width, 0, 0, cropped.height, cropped.width)
    }

    /**
     * Zero-copy attempt: the luma plane's own array is the luminance
     * source (dataWidth = rowStride). Only valid for heap-backed,
     * pixelStride-1 planes whose array starts at the frame origin.
     */
    private fun decodeDirect(frame: AnalysisFrame): Result? {
        val plane = frame.yPlane
        if (plane.pixelStride != 1 || plane.rowStride < frame.width) return null
        val buffer = plane.buffer.duplicate()
        if (!buffer.hasArray() || buffer.arrayOffset() != 0) return null
        val crop = frame.cropRect
        return try {
            val source = PlanarYUVLuminanceSource(
                buffer.array(), plane.rowStride, frame.height,
                crop.left, crop.top, crop.width, crop.height, false,
            )
            tryDecode(source)
        } catch (e: IllegalArgumentException) {
            Log.error("QrBarcodeScanner", "direct luminance source rejected", e)
            null
        }
    }

    private fun decodePacked(
        bytes: ByteArray, dataWidth: Int, dataHeight: Int,
        left: Int, top: Int, width: Int, height: Int,
    ): Result? {
        if (width <= 0 || height <= 0) return null
        return try {
            val source = PlanarYUVLuminanceSource(bytes, dataWidth, dataHeight, left, top, width, height, false)
            tryDecode(source)
        } catch (e: IllegalArgumentException) {
            Log.error("QrBarcodeScanner", "packed luminance source rejected", e)
            null
        }
    }

    private fun tryDecode(source: PlanarYUVLuminanceSource): Result? =
        try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
        } catch (_: NotFoundException) {
            null // expected: no code in this frame, not an error
        } catch (e: com.google.zxing.ChecksumException) {
            Log.error("QrBarcodeScanner", "ZXing decode threw", e)
            null
        } catch (e: com.google.zxing.FormatException) {
            Log.error("QrBarcodeScanner", "ZXing decode threw", e)
            null
        } finally {
            try {
                reader.reset()
            } catch (e: IllegalStateException) {
                Log.error("QrBarcodeScanner", "reader.reset() threw", e)
            }
        }

    private class PackedCrop(val bytes: ByteArray, val width: Int, val height: Int)

    /** Extracts [crop] out of a tightly packed full frame, or null if invalid. */
    private fun cropPacked(
        packed: ByteArray,
        frameWidth: Int,
        crop: com.vayunmathur.camera.domain.FrameCrop,
    ): PackedCrop? {
        if (crop.width <= 0 || crop.height <= 0) return null
        if (crop.left < 0 || crop.top < 0 || crop.right > frameWidth) return null
        if (crop.top + crop.height > packed.size / frameWidth) return null
        if (crop.isFullFrame(frameWidth, packed.size / frameWidth)) {
            return PackedCrop(packed, frameWidth, packed.size / frameWidth)
        }
        val out = ByteArray(crop.width * crop.height)
        var offset = 0
        for (row in 0 until crop.height) {
            val src = (crop.top + row) * frameWidth + crop.left
            System.arraycopy(packed, src, out, offset, crop.width)
            offset += crop.width
        }
        return PackedCrop(out, crop.width, crop.height)
    }

    /** Rotates a tightly packed luma image a quarter turn (dimensions swap). */
    private fun transpose(bytes: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(bytes.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[x * height + (height - 1 - y)] = bytes[y * width + x]
            }
        }
        return out
    }
}
