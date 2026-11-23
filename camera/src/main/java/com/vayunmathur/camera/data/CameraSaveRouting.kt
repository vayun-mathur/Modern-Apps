package com.vayunmathur.camera.data

import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import com.vayunmathur.library.log.Log
import androidx.core.content.ContextCompat
import java.io.OutputStream
import com.vayunmathur.camera.domain.StillFormat
import com.vayunmathur.camera.domain.StillFormatRouting
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.EncodedStill
import com.vayunmathur.camera.platform.StillEncoder.encode
import com.vayunmathur.camera.platform.buildAvifExifTiff

/**
 * Saves re-encoded bytes/bitmap to the current target: SAF doc when set
 * (created under [displayName]), else classic MediaStore insert. Runs the
 * blocking I/O on the calling thread — invoke from Dispatchers.IO.
 */
internal fun CameraViewModel.saveStillBytes(displayName: String, bytes: ByteArray): Uri? =
    saveStillBytes(displayName, bytes, StillFormatRouting.mimeType(StillFormat.JPEG))

/** Same as [saveStillBytes] for pre-encoded bytes in a non-JPEG container. */
internal fun CameraViewModel.saveStillBytes(displayName: String, bytes: ByteArray, mimeType: String): Uri? {
    // Capture-time breadcrumb at the byte-save seam: every pre-encoded
    // still (Camera2 JPEG/AVIF landing, Motion Photo, night, snapshots)
    // funnels through here, so this one line proves from logcat which
    // container a shot actually landed in. Log.debug, not Log.dev:
    // dev logs are silent in release builds, and this must be visible
    // on a release install. Payload-free (route/format/mime only).
    val formatName = if (mimeType == StillFormatRouting.mimeType(StillFormat.AVIF)) "AVIF" else "JPEG"
    val routeName = if (formatName == "AVIF") "processedEncoder" else "jpegBytes"
    val target = saveTargetMutable.value
    if (target is SaveTarget.SafTree) {
        val doc = SafDocuments.createImageDoc(app.contentResolver, target.treeUri, displayName, mimeType)
        if (doc != null) {
            MediaStoreSaver.saveJpegBytesToUri(app.contentResolver, doc, bytes)?.let {
                scanSafDoc(doc)
                Log.debug("StillSave", "route=$routeName format=$formatName mime=$mimeType")
                return it
            }
            Log.status("CameraViewModel", "SAF bytes save failed for $displayName; using MediaStore")
        }
    }
    return MediaStoreSaver.saveJpegBytes(
        app.contentResolver, MediaStoreSaver.imageValues(displayName, mimeType), bytes
    )?.also {
        Log.debug("StillSave", "route=$routeName format=$formatName mime=$mimeType")
    }
}

/** A saved processed still: where it landed and the container actually used. */
internal data class SavedStill(val uri: Uri, val format: StillFormat)

/**
 * Encodes a processed [bitmap] in the user's still format and saves it.
 * On the AVIF path the rotation is baked into the pixels first and a
 * TIFF payload — the same tags the JPEG path stamps (see
 * `buildAvifExifTiff`), orientation Normal, plus GPS when enabled — is
 * muxed in as the file's Exif item; JPEG keeps the bitmap as-is and
 * the caller stamps orientation via `writeCaptureExif` as before.
 * Falls back to JPEG bytes when the native AVIF encoder is
 * unavailable or no EXIF payload could be built (never an EXIF-less
 * AVIF). [sourceJpeg] is the capture's original JPEG bytes, when one
 * exists, used only as the EXIF tag source. [fileStem], when given,
 * replaces the `IMG_<timestamp>` stem (burst frame names).
 */
internal fun CameraViewModel.saveProcessedStill(
    bitmap: Bitmap,
    rotationDegrees: Int,
    sourceJpeg: ByteArray? = null,
    fileStem: String? = null,
): SavedStill? {
    val exifTiff = if (photoFormatMutable.value == com.vayunmathur.camera.platform.PhotoFormat.AVIF) {
        val carrier = java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, StillFormatRouting.JPEG_QUALITY, out)
            out.toByteArray()
        }
        buildAvifExifTiff(sourceJpeg, carrier)
    } else {
        null
    }
    val encoded: EncodedStill = encode(bitmap, rotationDegrees, exifTiff)
    val name = if (fileStem != null) {
        "$fileStem.${StillFormatRouting.extension(encoded.format)}"
    } else {
        StillFormatRouting.displayName(MediaStoreSaver.timestamp(), encoded.format)
    }
    val uri = saveStillBytes(name, encoded.bytes, StillFormatRouting.mimeType(encoded.format))
        ?: return null
    return SavedStill(uri, encoded.format)
}

/** Same as [saveStillBytes] for a [bitmap]. */
internal fun CameraViewModel.saveStillBitmap(displayName: String, bitmap: Bitmap): Uri? {
    val target = saveTargetMutable.value
    if (target is SaveTarget.SafTree) {
        val doc = SafDocuments.createImageDoc(app.contentResolver, target.treeUri, displayName)
        if (doc != null) {
            MediaStoreSaver.saveBitmapToUri(app.contentResolver, doc, bitmap)?.let {
                scanSafDoc(doc)
                return it
            }
            Log.status("CameraViewModel", "SAF bitmap save failed for $displayName; using MediaStore")
        }
    }
    return MediaStoreSaver.saveBitmap(
        app.contentResolver, MediaStoreSaver.imageValues(displayName), bitmap
    )
}

/** Copies a staged video [file] to the current target. Invoke from Dispatchers.IO. */
internal fun CameraViewModel.saveVideoStaged(displayName: String, file: java.io.File): Uri? {
    val target = saveTargetMutable.value
    if (target is SaveTarget.SafTree) {
        val doc = SafDocuments.createVideoDoc(app.contentResolver, target.treeUri, "$displayName.mp4")
        if (doc != null) {
            MediaStoreSaver.saveVideoFileToUri(app.contentResolver, doc, file)?.let {
                scanSafDoc(doc)
                return it
            }
            Log.status("CameraViewModel", "SAF video save failed for $displayName; using MediaStore")
        }
    }
    return MediaStoreSaver.saveVideoFile(
        app.contentResolver, MediaStoreSaver.videoValues(displayName), file
    )
}

/**
 * Best-effort media scan so SAF-saved shots appear in gallery apps when the
 * tree resolves to a real path on primary storage. No-op otherwise.
 */
internal fun CameraViewModel.scanSafDoc(docUri: Uri) {
    val target = saveTargetMutable.value as? SaveTarget.SafTree ?: return
    val base = SafDocuments.primaryPath(target.treeUri) ?: return
    val name = try {
        app.contentResolver.query(docUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    } ?: return
    try {
        MediaScannerConnection.scanFile(app, arrayOf("$base/$name"), null, null)
    } catch (e: IllegalStateException) {
        Log.status("CameraViewModel", "Media scan failed for $name", e)
    }
}

/** Executor helper shared by capture callbacks. */
internal fun CameraViewModel.mainExecutor(): java.util.concurrent.Executor =
    ContextCompat.getMainExecutor(app)
