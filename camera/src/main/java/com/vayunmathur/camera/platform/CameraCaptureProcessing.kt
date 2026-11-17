package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import com.vayunmathur.camera.data.prepareStillSave
import com.vayunmathur.camera.data.updateLocation

/** EXIF orientation buckets (degrees clockwise). */
private const val EXIF_DEGREES_90 = 90
private const val EXIF_DEGREES_180 = 180
private const val EXIF_DEGREES_270 = 270
private const val EXIF_FULL_CIRCLE_DEGREES = 360

/** Center-crop [src] to [rect] (the ImageProxy cropRect), returning [src] unchanged for a
 *  full-frame/empty rect. Recycles the pre-crop bitmap when a new one is produced. */
internal fun CameraViewModel.cropToRect(src: Bitmap, rect: Rect): Bitmap {
    val left = rect.left.coerceIn(0, src.width)
    val top = rect.top.coerceIn(0, src.height)
    val w = rect.width().coerceAtMost(src.width - left)
    val h = rect.height().coerceAtMost(src.height - top)
    if (isFullFrame(src, left, top, w, h)) return src
    return Bitmap.createBitmap(src, left, top, w, h).also { if (it != src) src.recycle() }
}

/** True when the crop covers the whole frame or is empty. */
private fun isFullFrame(src: Bitmap, left: Int, top: Int, w: Int, h: Int): Boolean {
    if (w <= 0 || h <= 0) return true
    if (left != 0 || top != 0) return false
    if (w != src.width || h != src.height) return false
    return true
}

/**
 * Rotates an already-processed capture's pixels upright, optionally scaling its long side down
 * to [maxSide] (0 = keep full resolution) for the result "data" thumbnail. Unlike
 * [downscaledThumbnail] it does not mirror — the processed bitmap already is. Always returns a
 * bitmap distinct from [src] so the caller can recycle it.
 */
internal fun uprightCopy(src: Bitmap, rotationDegrees: Int, maxSide: Int): Bitmap {
    val scale = if (maxSide > 0) {
        (maxSide.toFloat() / maxOf(src.width, src.height)).coerceAtMost(1f)
    } else 1f
    val matrix = Matrix().apply {
        postRotate(rotationDegrees.toFloat())
        if (scale < 1f) postScale(scale, scale)
    }
    val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    // createBitmap hands back the input itself when the transform is an identity.
    return if (out === src) src.copy(Bitmap.Config.ARGB_8888, false) else out
}

/**
 * Rotates the captured frame upright (mirroring for the front camera) and scales it down
 * for the result "data" thumbnail.
 */
internal fun downscaledThumbnail(image: ImageProxy, mirror: Boolean): Bitmap {
    val raw = image.toBitmap()
    val matrix = Matrix().apply {
        postRotate(image.imageInfo.rotationDegrees.toFloat())
        if (mirror) postScale(-1f, 1f)
    }
    val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    if (upright !== raw) raw.recycle()
    val maxSide = 512
    val scale = maxSide.toFloat() / maxOf(upright.width, upright.height)
    if (scale >= 1f) return upright
    return upright.scale(
        (upright.width * scale).roundToInt(),
        (upright.height * scale).roundToInt()
    ).also {
        if (it !== upright) upright.recycle()
    }
}

/**
 * Bakes the warmth/shadows color matrix into [src] (and horizontally mirrors it when [mirror]
 * is set, for front-camera parity with the preview), returning a new bitmap and recycling
 * [src]. Rotation is intentionally left to the EXIF orientation tag (see [CameraViewModel.writeCaptureExif]),
 * mirroring how the normal ImageCapture path stores orientation without rotating pixels.
 */
internal fun applyColorAdjustments(src: Bitmap, warmth: Float, shadows: Float, mirror: Boolean): Bitmap {
    val out = createBitmap(src.width, src.height)
    val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(buildColorAdjustmentMatrix(warmth, shadows))
    }
    val canvas = Canvas(out)
    if (mirror) canvas.scale(-1f, 1f, src.width / 2f, src.height / 2f)
    canvas.drawBitmap(src, 0f, 0f, paint)
    src.recycle()
    return out
}

/**
 * Restores the EXIF that re-encoding a processed bitmap dropped: copies the original frame's
 * metadata tags (when [sourceJpeg] is provided), writes the orientation for [rotationDegrees],
 * and stamps GPS when location is enabled. For the night merge there is no single source frame,
 * so [sourceJpeg] is null and only orientation + GPS are written.
 */
internal fun CameraViewModel.writeCaptureExif(
    uri: Uri,
    sourceJpeg: ByteArray?,
    rotationDegrees: Int,
    mirrored: Boolean = false
) {
    // Refresh the fix at capture time (see prepareStillSave) — this runs on Dispatchers.IO
    // and getLastKnownLocation is a cheap cached lookup, so no main-thread concern.
    updateLocation()
    try {
        writeExifAttributes(uri, sourceJpeg, rotationDegrees, mirrored)
    } catch (e: java.io.IOException) {
        Log.status("CameraViewModel", "Failed to write EXIF for adjusted capture", e)
    } catch (e: SecurityException) {
        Log.status("CameraViewModel", "Failed to write EXIF for adjusted capture", e)
    }
}

/** Opens [uri] for EXIF read/write and stamps orientation (+GPS +copied tags). */
private fun CameraViewModel.writeExifAttributes(
    uri: Uri,
    sourceJpeg: ByteArray?,
    rotationDegrees: Int,
    mirrored: Boolean
) {
    val source = sourceJpeg?.let { ExifInterface(ByteArrayInputStream(it)) }
    app.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
        val dest = ExifInterface(pfd.fileDescriptor)
        copyExifTags(source, dest)
        dest.setAttribute(ExifInterface.TAG_ORIENTATION, orientationString(rotationDegrees, mirrored))
        if (locationEnabledMutable.value) lastLocation?.let { dest.setGpsInfo(it) }
        dest.saveAttributes()
    }
}

/** Copies the preserved metadata tags from the source frame, if any. */
private fun copyExifTags(source: ExifInterface?, dest: ExifInterface) {
    source?.let { src ->
        CameraViewModel.EXIF_TAGS_TO_COPY.forEach { tag ->
            src.getAttribute(tag)?.let { dest.setAttribute(tag, it) }
        }
    }
}

/**
 * EXIF orientation tag for [rotationDegrees], inverted when the pixels were already
 * mirrored (front camera): flip and rotate don't commute, and FLIP∘ROTATE(θ)∘FLIP =
 * ROTATE(−θ). Without this, portrait selfies (θ=90/270) save upside down; landscape
 * (θ=0/180) commutes and is unaffected.
 */
private fun orientationString(rotationDegrees: Int, mirrored: Boolean): String {
    val effectiveDegrees =
        if (mirrored) (EXIF_FULL_CIRCLE_DEGREES - rotationDegrees) % EXIF_FULL_CIRCLE_DEGREES
        else rotationDegrees
    return when (effectiveDegrees) {
        EXIF_DEGREES_90 -> ExifInterface.ORIENTATION_ROTATE_90
        EXIF_DEGREES_180 -> ExifInterface.ORIENTATION_ROTATE_180
        EXIF_DEGREES_270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }.toString()
}

/**
 * Stamps orientation + GPS into an in-memory still's JPEG [jpeg] and returns the updated
 * bytes. In-memory captures carry no ImageCapture.Metadata, so unlike the OutputFileOptions
 * path (see prepareStillSave) nothing stamps location for them — that is why plain PHOTO
 * shots (Motion Photo path) lost GPS while Portrait (which goes through [writeCaptureExif])
 * kept it (issue #731).
 *
 * Stamping happens BEFORE any Motion Photo trailer is appended so ExifInterface only ever
 * rewrites a pure JPEG. When location is off only orientation is stamped (no GPS rewrite);
 * the Ultra HDR gain map survives either way since ExifInterface preserves it.
 * Must be called off the main thread (file I/O).
 */
internal fun CameraViewModel.stampStillBytes(
    jpeg: ByteArray,
    rotationDegrees: Int,
    mirrored: Boolean = false
): ByteArray {
    updateLocation()
    val loc = if (locationEnabledMutable.value) lastLocation else null
    // In-memory captures carry no ImageCapture.Metadata, so orientation must ALWAYS be
    // stamped — not just when GPS is on — or motion shots save sideways with location off.
    // Mirrored front shots need the inverted rotation (FLIP∘ROTATE(θ)∘FLIP = ROTATE(−θ)),
    // matching writeCaptureExif().
    val effectiveDegrees =
        if (mirrored) (EXIF_FULL_CIRCLE_DEGREES - rotationDegrees) % EXIF_FULL_CIRCLE_DEGREES
        else rotationDegrees
    var tmp: java.io.File? = null
    return try {
        tmp = java.io.File.createTempFile("stamp_", ".jpg", app.cacheDir)
        tmp.writeBytes(jpeg)
        stampTempFile(tmp, effectiveDegrees, loc)
        tmp.readBytes()
    } catch (e: java.io.IOException) {
        Log.status("CameraViewModel", "Failed to stamp EXIF on still bytes", e)
        jpeg
    } catch (e: SecurityException) {
        Log.status("CameraViewModel", "Failed to stamp EXIF on still bytes", e)
        jpeg
    } finally {
        try { tmp?.delete() } catch (_: Exception) {}
    }
}

/** Stamps orientation (+GPS when [loc] is non-null) into the temp JPEG file. */
private fun stampTempFile(tmp: java.io.File, effectiveDegrees: Int, loc: android.location.Location?) {
    val exif = ExifInterface(tmp.absolutePath)
    exif.setAttribute(
        ExifInterface.TAG_ORIENTATION,
        when (
            (effectiveDegrees % EXIF_FULL_CIRCLE_DEGREES + EXIF_FULL_CIRCLE_DEGREES) %
                EXIF_FULL_CIRCLE_DEGREES
        ) {
            EXIF_DEGREES_90 -> ExifInterface.ORIENTATION_ROTATE_90
            EXIF_DEGREES_180 -> ExifInterface.ORIENTATION_ROTATE_180
            EXIF_DEGREES_270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }.toString()
    )
    // GPS only when the user opted in; orientation is always stamped above. Skipping
    // setGpsInfo with no location keeps the bytes bit-for-bit (Ultra HDR gain map intact).
    if (loc != null) exif.setGpsInfo(loc)
    exif.saveAttributes()
}
