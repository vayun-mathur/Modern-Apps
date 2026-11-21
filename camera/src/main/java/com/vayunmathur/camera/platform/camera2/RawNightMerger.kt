package com.vayunmathur.camera.platform.camera2

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vayunmathur.camera.platform.NightCaptureEngine
import com.vayunmathur.camera.platform.StitchNative
import com.vayunmathur.library.log.Log

/**
 * Camera2 depth Phase 2: Kotlin side of the RAW night merge. Copies each
 * held RAW frame's plane (tightening row stride first — Rust unpackers
 * assume tight rows), feeds the packed bytes plus the facade's black /
 * white level, CFA and the frame's white-balance gains to the Rust
 * linear merge, then hands the tone-mapped JPEG through the existing
 * night tone curve. Returns null on any gap so the caller falls back to
 * the YUV night path — a RAW failure never loses the shutter.
 */
object RawNightMerger {

    /** RAW frames in one night burst (matches the YUV burst count). */
    const val RAW_BURST_COUNT: Int = 6

    fun merge(
        frames: List<RawHeldFrame>,
        snapshot: LensCharacteristicsSnapshot,
        previewMetadataFor: (Long) -> RawStillMetadata?,
    ): Bitmap? {
        if (!StitchNative.isAvailable || frames.size < 2) return null
        val white = snapshot.rawWhiteLevel?.toFloat() ?: run {
            Log.status("RawNight", "no white level; RAW merge unavailable")
            return null
        }
        val black = snapshot.rawBlackLevelPattern
            .takeIf { it.size == 4 }?.average()?.toFloat() ?: run {
            Log.status("RawNight", "no black level pattern; RAW merge unavailable")
            return null
        }
        val cfa = snapshot.colorFilterArrangement ?: run {
            Log.status("RawNight", "no CFA arrangement; RAW merge unavailable")
            return null
        }
        val refMeta = frames.first().meta
        val refExposure = exposureProduct(refMeta, previewMetadataFor(refMeta.sensorTimestampNs))
        val handle = StitchNative.newRawNightSession()
        if (handle == 0L) return null
        try {
            for (frame in frames) {
                val rawMeta = previewMetadataFor(frame.meta.sensorTimestampNs)
                val gains = rawMeta?.colorCorrectionGains
                val exposure = exposureProduct(frame.meta, rawMeta)
                val bytes = tightRows(frame) ?: continue
                StitchNative.addRawFrame(
                    handle, bytes, frame.meta.width, frame.meta.height,
                    frame.format.rawBitDepth(),
                    black, white, cfa,
                    gains?.getOrNull(0) ?: 1f,
                    gains?.let {
                        val g0 = it.getOrNull(1) ?: 1f
                        val g1 = it.getOrNull(2) ?: g0
                        (g0 + g1) * 0.5f
                    } ?: 1f,
                    gains?.getOrNull(3) ?: 1f,
                    if (refExposure > 0 && exposure > 0) {
                        (exposure / refExposure).toFloat()
                    } else {
                        1f
                    },
                )
            }
            val jpeg = StitchNative.mergeRawNight(handle, 1.0f) ?: return null
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
            val toned = NightCaptureEngine.applyNightTone(bitmap)
            if (toned !== bitmap) bitmap.recycle()
            return toned
        } catch (e: UnsatisfiedLinkError) {
            Log.status("RawNight", "native RAW merge unavailable; falling back", e)
            return null
        } finally {
            try {
                StitchNative.freeRawNight(handle)
            } catch (e: UnsatisfiedLinkError) {
                Log.status("RawNight", "freeRawNight failed", e)
            }
        }
    }

    private fun exposureProduct(meta: RingFrameMeta, raw: RawStillMetadata?): Double {
        val exposureNs = meta.exposureTimeNs ?: raw?.exposureTimeNs ?: return 0.0
        val iso = meta.iso ?: raw?.iso ?: return 0.0
        return exposureNs.toDouble() * iso.toDouble()
    }

    /** Strips row-stride padding so Rust sees tight RAW rows. */
    private fun tightRows(frame: RawHeldFrame): ByteArray? {
        val bytes = frame.copyRawBytes() ?: return null
        val width = frame.meta.width
        val height = frame.meta.height
        if (width <= 0 || height <= 0) return null
        val tightRow = when (frame.format) {
            RingFormat.RAW10 -> width / 4 * 5
            else -> width * 2
        }
        val stride = frame.rowStrideBytes
        if (stride <= tightRow || stride == 0) {
            return if (bytes.size >= tightRow * height) bytes.copyOf(tightRow * height) else null
        }
        if (bytes.size < stride * height) return null
        val out = ByteArray(tightRow * height)
        for (row in 0 until height) {
            bytes.copyInto(out, row * tightRow, row * stride, row * stride + tightRow)
        }
        return out
    }
}
