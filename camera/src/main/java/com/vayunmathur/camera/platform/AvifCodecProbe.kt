package com.vayunmathur.camera.platform

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.vayunmathur.library.log.Log

/** MIME type a still-image AVIF encoder would register under. */
internal const val AVIF_IMAGE_MIME = "image/avif"

/** MIME type of AV1 video; a video encoder is NOT a still AVIF encoder. */
internal const val AV1_VIDEO_MIME = "video/av01"

/** One encoder registry entry relevant to AVIF/AV1. */
internal data class AvifCodecEntry(
    val name: String,
    val mime: String,
    val hardwareAccelerated: Boolean,
    val maxWidth: Int?,
    val maxHeight: Int?,
)

/**
 * Pure classification of codec-registry rows: which entries could encode
 * a still AVIF. Kept Android-free (rows in, verdict out) so the routing
 * rule is host-testable; [AvifCodecProbe] supplies the real rows.
 */
internal object AvifCodecRouting {

    /** A usable still encoder: an `image/avif` encoder (hardware or not). */
    fun stillEncoder(entries: List<AvifCodecEntry>): AvifCodecEntry? =
        entries.firstOrNull { it.mime.equals(AVIF_IMAGE_MIME, ignoreCase = true) }

    /** True when that still encoder is hardware-accelerated. */
    fun hasHardwareStillEncoder(entries: List<AvifCodecEntry>): Boolean =
        stillEncoder(entries)?.hardwareAccelerated == true

    /**
     * True when the frame fits the encoder's advertised size cap
     * (a null cap means the registry published no video/size limits,
     * which image codecs commonly don't — accept and let configure fail).
     */
    fun fitsSize(entry: AvifCodecEntry, width: Int, height: Int): Boolean =
        (entry.maxWidth == null || width <= entry.maxWidth) &&
            (entry.maxHeight == null || height <= entry.maxHeight)
}

/**
 * Cached [MediaCodecList] probe for a hardware still-AVIF encoder.
 *
 * Enumeration is warmed once at startup (see `runStartupSequence`) on a
 * background dispatcher, so the capture path only reads the cached
 * verdict. A `video/av01` encoder — the only AV1 encoder Pixel-class
 * devices ship, and a software Google codec at that — cannot produce a
 * still AVIF file (no ISOBMFF still mux, no single-image mode in the
 * MediaCodec image contract), so it is logged as evidence but never
 * routed to; only an `image/avif` encoder would be.
 */
internal object AvifCodecProbe {

    @Volatile
    private var cached: List<AvifCodecEntry>? = null

    /** All AVIF/AV1 encoder entries, enumerated at most once. */
    fun entries(): List<AvifCodecEntry> = cached ?: synchronized(this) {
        cached ?: enumerate().also { cached = it }
    }

    /** Warms the cache and logs the verdict. Call off the main thread. */
    fun warmUp() {
        val entries = entries()
        val still = AvifCodecRouting.stillEncoder(entries)
        Log.debug(
            "AvifCodec",
            "image/avif encoder=${still?.name ?: "none"} " +
                "hardware=${still?.hardwareAccelerated ?: false} " +
                "av1VideoEncoders=${entries.filter { it.mime == AV1_VIDEO_MIME }
                    .joinToString(",") { "${it.name}(hw=${it.hardwareAccelerated})" }
                    .ifEmpty { "none" }}",
        )
    }

    /** The still-AVIF encoder to route through, or null for software ravif. */
    fun stillEncoder(): AvifCodecEntry? = AvifCodecRouting.stillEncoder(entries())

    private fun enumerate(): List<AvifCodecEntry> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { info ->
                info.supportedTypes
                    .filter {
                        it.equals(AVIF_IMAGE_MIME, ignoreCase = true) ||
                            it.equals(AV1_VIDEO_MIME, ignoreCase = true)
                    }
                    .map { mime -> entry(info, mime) }
            }
    } catch (e: Exception) {
        Log.status("AvifCodec", "MediaCodecList enumeration failed; software AVIF only", e)
        emptyList()
    }

    private fun entry(info: MediaCodecInfo, mime: String): AvifCodecEntry {
        val caps = try {
            info.getCapabilitiesForType(mime)
        } catch (_: IllegalArgumentException) {
            null
        }
        val video = caps?.videoCapabilities
        return AvifCodecEntry(
            name = info.name,
            mime = mime,
            hardwareAccelerated = try {
                info.isHardwareAccelerated
            } catch (_: Exception) {
                false
            },
            maxWidth = video?.supportedWidths?.upper,
            maxHeight = video?.supportedHeights?.upper,
        )
    }
}
