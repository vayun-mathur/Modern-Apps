package com.vayunmathur.camera.domain

/** Still container the user picked in camera settings. */
enum class StillFormat {
    JPEG,
    AVIF,
}

/**
 * Pure routing decisions for still saving: which container a capture
 * actually lands in. Kept Android-free so the fallback rules are unit
 * tested on the host.
 */
object StillFormatRouting {

    /** JPEG quality used for the platform/Bitmap JPEG fallback path. */
    const val JPEG_QUALITY = 95

    /** AVIF quality (ravif scale 1..100) for the native encoder. */
    const val AVIF_QUALITY = 80f

    /**
     * AVIF speed (rav1e scale 1..10, 10 = fastest) for stills.
     *
     * Measured on a photo-like 4080x3072 (12 MP) frame at quality 80
     * (host, release ravif; PSNR scored by decoding with dav1d):
     * speed 8 took 3,248 ms with default threading (7,728 ms pinned to
     * 8 threads, the phone-like core count) at PSNR 40.20 dB /
     * 2,205,501 bytes; speed 10 took 424 ms (1,698 ms at 8 threads) at
     * PSNR 40.14 dB / 2,284,322 bytes. So speed 10 is ~4.6x faster at
     * 8 threads for -0.06 dB PSNR (within the speed 8<->9 spread) and
     * +3.6% file size — the quantizer (quality target) is unchanged,
     * the cost is only marginally less optimal partition/tx decisions.
     * Speeds 4/6/9 were all >=2.9 s default-threaded with no PSNR gain
     * over speed 10 worth the latency, so 10 is the knee.
     */
    const val AVIF_SPEED = 10

    /**
     * The format a still is really saved as. AVIF is only honoured when
     * the native encoder is present; Motion Photos always stay JPEG
     * because the format is a JPEG with an appended MP4 trailer.
     */
    fun effectiveFormat(requested: StillFormat, nativeAvailable: Boolean, motionPhoto: Boolean): StillFormat =
        when {
            motionPhoto -> StillFormat.JPEG
            requested == StillFormat.AVIF && !nativeAvailable -> StillFormat.JPEG
            else -> requested
        }

    /** File extension (no dot) matching [format]. */
    fun extension(format: StillFormat): String = when (format) {
        StillFormat.JPEG -> "jpg"
        StillFormat.AVIF -> "avif"
    }

    /** MediaStore MIME type matching [format]. */
    fun mimeType(format: StillFormat): String = when (format) {
        StillFormat.JPEG -> "image/jpeg"
        StillFormat.AVIF -> "image/avif"
    }

    /** `IMG_<timestamp>` display name in the container's extension. */
    fun displayName(timestamp: String, format: StillFormat): String =
        "IMG_$timestamp.${extension(format)}"

    /**
     * True when a plain (unprocessed) single must leave the CameraX file
     * path and be captured in memory instead: only an AVIF-bound still
     * needs that (the file path can only write the HAL JPEG). JPEG, and
     * AVIF with no native encoder (which honestly falls back to JPEG),
     * stay on the file path so EXIF/Ultra HDR are never re-encoded away.
     */
    fun plainSingleNeedsInMemory(requested: StillFormat, nativeAvailable: Boolean): Boolean =
        effectiveFormat(requested, nativeAvailable, motionPhoto = false) == StillFormat.AVIF

    /**
     * True when an AVIF-bound plain still must not divert to Motion Photo:
     * a Motion Photo is a JPEG with an MP4 trailer, so honouring the AVIF
     * selection means taking the plain still (and its AVIF encode) instead.
     * JPEG-selected shots keep the Motion Photo diversion unchanged.
     */
    fun avifPrecludesMotionPhoto(requested: StillFormat, nativeAvailable: Boolean): Boolean =
        plainSingleNeedsInMemory(requested, nativeAvailable)

    /** How one pre-encoded still (HAL JPEG, ring encode, extension JPEG) must land. */
    enum class StillSaveRoute {
        /** Decode once and re-encode through the still encoder (AVIF when selected). */
        PROCESSED_ENCODER,

        /** Stamp the source JPEG bytes and write them unchanged. */
        JPEG_BYTES,

        /** Ultra HDR (JPEG_R) source: write the bytes unchanged, gain map intact. */
        ULTRA_HDR_JPEG_BYTES,
    }

    /**
     * The landing for a pre-encoded still under [requested]. An Ultra HDR
     * source always stays JPEG_R (the AVIF gain-map mux is not built);
     * AVIF otherwise routes through the encoder; JPEG stays JPEG bytes.
     */
    fun stillSaveRoute(requested: StillFormat, ultraHdrSource: Boolean): StillSaveRoute = when {
        ultraHdrSource -> StillSaveRoute.ULTRA_HDR_JPEG_BYTES
        requested == StillFormat.AVIF -> StillSaveRoute.PROCESSED_ENCODER
        else -> StillSaveRoute.JPEG_BYTES
    }

    /**
     * The TIFF payload of a JPEG's Exif APP1 segment (`Exif\0\0` stripped),
     * or null when the JPEG carries none. Pure segment walk (no Android
     * types) so it is host-testable; used to feed the AVIF Exif item.
     */
    fun extractExifTiff(jpeg: ByteArray): ByteArray? {
        for ((marker, payload) in jpegHeaderSegments(jpeg)) {
            if (marker == 0xE1 && payload.size > 6 &&
                payload[0] == 'E'.code.toByte() && payload[1] == 'x'.code.toByte() &&
                payload[2] == 'i'.code.toByte() && payload[3] == 'f'.code.toByte() &&
                payload[4].toInt() == 0 && payload[5].toInt() == 0
            ) {
                return payload.copyOfRange(6, payload.size)
            }
        }
        return null
    }

    /**
     * True when [jpeg] is an Ultra HDR (JPEG_R) still: its header segments
     * advertise a gain map (Adobe `hdrgm` XMP / HDRGainMap, an ISO 21496-1
     * gain-map container item, or an MPF `MP Extension` second image).
     * Only header segments (before SOS) are scanned, so compressed image
     * data can never false-positive. Such stills stay JPEG_R when AVIF is
     * selected — decoding them would discard the gain map.
     */
    fun isUltraHdrJpeg(jpeg: ByteArray): Boolean {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return false
        val needles = listOf("hdrgm", "hdr-gain-map", "HDRGainMap", "GainMap", "MP Extension")
        for ((_, payload) in jpegHeaderSegments(jpeg)) {
            val text = String(payload, Charsets.ISO_8859_1)
            if (needles.any { it in text }) return true
        }
        return false
    }

    /**
     * Header segments of a JPEG as (marker, payload) pairs, stopping at
     * SOS (start of compressed data) or the first malformed segment.
     */
    private fun jpegHeaderSegments(jpeg: ByteArray): List<Pair<Int, ByteArray>> {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return emptyList()
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (jpeg[pos] != 0xFF.toByte()) break
            var markerPos = pos + 1
            while (markerPos < jpeg.size && jpeg[markerPos] == 0xFF.toByte()) markerPos++
            if (markerPos >= jpeg.size) break
            val marker = jpeg[markerPos].toInt() and 0xFF
            if (marker == 0xDA) break // SOS: compressed data follows
            // Standalone markers (SOI/EOI/TEM/RSTn) carry no length.
            if (marker == 0xD8 || marker == 0xD9 || marker == 0x01 || marker in 0xD0..0xD7) {
                pos = markerPos + 1
                continue
            }
            if (markerPos + 3 > jpeg.size) break
            val length = ((jpeg[markerPos + 1].toInt() and 0xFF) shl 8) or
                (jpeg[markerPos + 2].toInt() and 0xFF)
            if (length < 2 || markerPos + 1 + length > jpeg.size) break
            val payloadStart = markerPos + 3
            val payloadEnd = markerPos + 1 + length
            out.add(marker to jpeg.copyOfRange(payloadStart, payloadEnd))
            pos = payloadEnd
        }
        return out
    }
}
