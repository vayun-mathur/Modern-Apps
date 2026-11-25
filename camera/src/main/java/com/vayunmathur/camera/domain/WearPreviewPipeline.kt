package com.vayunmathur.camera.domain

/**
 * Pure live-viewfinder pipeline for the watch preview: downscale maths,
 * the latest-frame-only hand-off, the adaptive rate controller and the
 * encode step behind a fakeable seam. No Android types here — the phone
 * feeds ARGB pixels from its existing Camera2 analysis stream (via
 * Yuv420ToArgb) and the platform layer supplies the real JPEG encoder.
 *
 * Ordering guarantee (tested): pixels are downscaled to watch size FIRST
 * and encoded once at that size. The full-size analysis frame is never
 * encoded-then-shrunk, which would waste the analysis thread and the
 * RFCOMM link on pixels the watch cannot show.
 */

/** Watch-sized output dimensions for a source frame; never upscales. */
fun previewDimensions(srcWidth: Int, srcHeight: Int, maxWidth: Int = WearCameraProtocol.PREVIEW_MAX_WIDTH): Pair<Int, Int> {
    if (srcWidth <= 0 || srcHeight <= 0) return 1 to 1
    if (srcWidth <= maxWidth) return srcWidth to srcHeight
    val scaledHeight = (srcHeight.toLong() * maxWidth / srcWidth).toInt().coerceAtLeast(1)
    return maxWidth to scaledHeight
}

/** Nearest-neighbour ARGB downscale (upscale requests return a copy at source size). */
fun downscaleArgb(argb: IntArray, srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): IntArray {
    require(argb.size >= srcWidth * srcHeight) { "pixel buffer smaller than frame" }
    if (dstWidth == srcWidth && dstHeight == srcHeight) return argb.copyOf(srcWidth * srcHeight)
    val out = IntArray(dstWidth * dstHeight)
    for (y in 0 until dstHeight) {
        val srcY = y * srcHeight / dstHeight
        for (x in 0 until dstWidth) {
            val srcX = x * srcWidth / dstWidth
            out[y * dstWidth + x] = argb[srcY * srcWidth + srcX]
        }
    }
    return out
}

/** JPEG encoder seam: the platform supplies Bitmap.compress; tests record calls. */
interface PreviewJpegEncoder {
    fun encode(argb: IntArray, width: Int, height: Int, quality: Int): ByteArray
}

/** One encoded preview frame, ready for [PreviewFrame.encode]. */
data class EncodedPreview(val jpeg: ByteArray, val width: Int, val height: Int, val quality: Int) {
    override fun equals(other: Any?): Boolean =
        other is EncodedPreview && width == other.width && height == other.height &&
            quality == other.quality && jpeg.contentEquals(other.jpeg)

    override fun hashCode(): Int {
        var r = jpeg.contentHashCode()
        r = 31 * r + width
        r = 31 * r + height
        r = 31 * r + quality
        return r
    }
}

/**
 * Downscale-then-encode processor with size-adaptive quality: an encoded
 * frame over [WearCameraProtocol.PREVIEW_LARGE_FRAME_BYTES] steps the
 * next quality down (floor [WearCameraProtocol.PREVIEW_MIN_JPEG_QUALITY]),
 * a comfortably small one steps it back up (ceiling
 * [WearCameraProtocol.PREVIEW_MAX_JPEG_QUALITY]). One encode per frame,
 * always at the downscaled size.
 */
class PreviewFrameProcessor(private val encoder: PreviewJpegEncoder) {
    var nextQuality: Int = WearCameraProtocol.PREVIEW_JPEG_QUALITY
        private set

    fun process(argb: IntArray, srcWidth: Int, srcHeight: Int): EncodedPreview? {
        if (srcWidth <= 0 || srcHeight <= 0 || argb.size < srcWidth * srcHeight) return null
        val (width, height) = previewDimensions(srcWidth, srcHeight)
        val pixels = downscaleArgb(argb, srcWidth, srcHeight, width, height)
        val quality = nextQuality
        val jpeg = encoder.encode(pixels, width, height, quality)
        nextQuality = when {
            jpeg.size > WearCameraProtocol.PREVIEW_LARGE_FRAME_BYTES ->
                (quality - QUALITY_STEP).coerceAtLeast(WearCameraProtocol.PREVIEW_MIN_JPEG_QUALITY)
            jpeg.size < WearCameraProtocol.PREVIEW_LARGE_FRAME_BYTES / 2 ->
                (quality + QUALITY_STEP).coerceAtMost(WearCameraProtocol.PREVIEW_MAX_JPEG_QUALITY)
            else -> quality
        }
        return EncodedPreview(jpeg, width, height, quality)
    }

    private companion object {
        const val QUALITY_STEP = 5
    }
}

/**
 * Latest-frame-only hand-off between the analysis thread (producer) and
 * the preview pump (consumer): at most one frame waits. An offer made
 * while a frame is still waiting is dropped and counted — the producer
 * (the phone's capture/analysis path) never blocks and never queues
 * stale frames behind a slow encode or a slow link.
 */
class LatestOnlySlot<T> {
    private var held: T? = null

    var droppedCount: Long = 0L
        private set

    var offeredCount: Long = 0L
        private set

    val isOccupied: Boolean get() = held != null

    /** Stores [frame] when the slot is free; returns false (drop) when occupied. */
    @Synchronized
    fun offer(frame: T): Boolean {
        offeredCount++
        if (held != null) {
            droppedCount++
            return false
        }
        held = frame
        return true
    }

    /** Takes the waiting frame, freeing the slot; null when empty. */
    @Synchronized
    fun take(): T? {
        val frame = held
        held = null
        return frame
    }

    @Synchronized
    fun clear() {
        held = null
    }
}

/**
 * Adaptive preview rate: starts at the nominal rate, backs off toward
 * [WearCameraProtocol.PREVIEW_MIN_FPS] when a frame's encode+send takes
 * longer than the frame interval (the link is the bottleneck), and
 * recovers toward [WearCameraProtocol.PREVIEW_MAX_FPS] after sustained
 * fast sends. Deterministic integer maths so tests pin the adaptation.
 */
class AdaptivePreviewRate {
    var fps: Int = WearCameraProtocol.PREVIEW_NOMINAL_FPS
        private set

    private var fastStreak = 0

    val intervalMillis: Long get() = 1_000L / fps

    /** Records one frame's encode+send duration and adapts the rate. */
    fun onFrameSent(durationMillis: Long) {
        when {
            durationMillis > intervalMillis -> {
                fps = (fps - 1).coerceAtLeast(WearCameraProtocol.PREVIEW_MIN_FPS)
                fastStreak = 0
            }
            durationMillis * FAST_FACTOR < intervalMillis -> {
                fastStreak++
                if (fastStreak >= FAST_STREAK_TO_SPEED_UP) {
                    fps = (fps + 1).coerceAtMost(WearCameraProtocol.PREVIEW_MAX_FPS)
                    fastStreak = 0
                }
            }
            else -> fastStreak = 0
        }
    }

    /** A dropped/failed send backs the rate off one step immediately. */
    fun onSendFailed() {
        fps = (fps - 1).coerceAtLeast(WearCameraProtocol.PREVIEW_MIN_FPS)
        fastStreak = 0
    }

    fun reset() {
        fps = WearCameraProtocol.PREVIEW_NOMINAL_FPS
        fastStreak = 0
    }

    private companion object {
        const val FAST_FACTOR = 2L
        const val FAST_STREAK_TO_SPEED_UP = 4
    }
}
