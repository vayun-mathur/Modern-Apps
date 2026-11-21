@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.translate.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Size
import androidx.compose.ui.geometry.Offset

/** Minimum gap between the *end* of one OCR pass and the start of the next (ms). */
internal const val ANALYSIS_INTERVAL_MS = 400L

/**
 * Resolution requested for the analysis stream, in sensor (landscape) orientation.
 * CameraX defaults ImageAnalysis to 640×480, which is far too coarse for PP-OCRv5 to
 * detect anything but very large text — this is the single biggest reason the camera
 * used to look like it "saw" nothing. 4:3 matches the preview stream's aspect ratio,
 * which the overlay mapping below relies on.
 */
internal val ANALYSIS_SIZE = Size(1280, 960)

/** Upper bound on the translation memo; plenty for a screenful of lines. */
internal const val TRANSLATION_CACHE_MAX = 128

/** Idle poll while every visible line already has a translation (ms). */
internal const val TRANSLATE_IDLE_POLL_MS = 100L

/**
 * One detected line, as its oriented corners in the (upright) analysed-bitmap's
 * pixel space, in reading order (corner 0 -> 1 runs along the text, 0 -> 3 spans
 * its height). Holds the *source* text.
 */
internal data class OverlayBox(
    val corners: List<Offset>,
    val text: String,
)

/**
 * Take [crop] out of [src] and rotate it by [degrees], so OCR sees an upright image of
 * just the region the preview is showing. May return [src] itself when there is nothing
 * to do, so callers must compare identities before recycling.
 */
/**
 * Clamp [crop] to a `srcWidth` x `srcHeight` frame exactly as
 * [cropAndRotate] always has: left/top into the frame, the crop's own
 * size to what remains, and an empty result falling back to the whole
 * frame. Shared by the Bitmap path and [uprightFrameSize] so the buffer
 * path's native clamp (which mirrors this in Rust) and both Kotlin paths
 * cannot drift. Right/bottom are exclusive in the result, as in [Rect].
 */
internal fun clampedCrop(srcWidth: Int, srcHeight: Int, crop: Rect): Rect {
    val left = crop.left.coerceIn(0, srcWidth)
    val top = crop.top.coerceIn(0, srcHeight)
    var width = crop.width().coerceIn(0, srcWidth - left)
    var height = crop.height().coerceIn(0, srcHeight - top)
    // An unresolved viewport gives an empty rect; fall back to the whole frame.
    if (width <= 0 || height <= 0) {
        width = srcWidth
        height = srcHeight
        return Rect(0, 0, width, height)
    }
    return Rect(left, top, left + width, top + height)
}

/**
 * The size of the upright frame [cropAndRotate] would produce from a
 * `srcWidth` x `srcHeight` frame — quarter turns swap the axes, other
 * rotations keep them guarded by callers to quarter turns only. This is
 * what the plane-direct path reports as the analysed frame's size,
 * since native does the crop and rotation and no Bitmap exists to ask.
 */
internal fun uprightFrameSize(srcWidth: Int, srcHeight: Int, crop: Rect, degrees: Int): Size {
    val clamped = clampedCrop(srcWidth, srcHeight, crop)
    return when ((degrees % 360 + 360) % 360) {
        90, 270 -> Size(clamped.height(), clamped.width())
        else -> Size(clamped.width(), clamped.height())
    }
}

/** Whether a camera plane may take the direct-buffer OCR path at all. */
internal fun bufferPathEligible(isDirect: Boolean, pixelStride: Int, rotationDegrees: Int): Boolean =
    isDirect && pixelStride == RGBA_PIXEL_STRIDE && (rotationDegrees % 360 + 360) % 90 == 0

/** Bytes per pixel (and the required pixel stride) of RGBA_8888 planes. */
internal const val RGBA_PIXEL_STRIDE = 4

internal fun cropAndRotate(src: Bitmap, crop: Rect, degrees: Int): Bitmap {
    val clamped = clampedCrop(src.width, src.height, crop)
    val left = clamped.left
    val top = clamped.top
    val width = clamped.width()
    val height = clamped.height()
    if (width <= 0 || height <= 0) return src
    val matrix = if (degrees % 360 == 0) null else Matrix().apply { postRotate(degrees.toFloat()) }
    val whole = left == 0 && top == 0 && width == src.width && height == src.height
    if (matrix == null && whole) return src
    return Bitmap.createBitmap(src, left, top, width, height, matrix, true)
}

internal const val TAG = "CameraTranslate"
