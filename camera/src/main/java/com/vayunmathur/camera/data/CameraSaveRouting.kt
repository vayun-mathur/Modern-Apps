package com.vayunmathur.camera.data

import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import com.vayunmathur.library.log.Log
import androidx.camera.core.ImageCapture
import androidx.core.content.ContextCompat
import java.io.OutputStream
import com.vayunmathur.camera.domain.StillFormat
import com.vayunmathur.camera.domain.StillFormatRouting
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.EncodedStill
import com.vayunmathur.camera.platform.StillEncoder.encode
import com.vayunmathur.camera.platform.mirrorCaptures

/**
 * Shared save-target routing for still captures. When the user picked a SAF
 * folder, creates a document there and builds stream-backed
 * [ImageCapture.OutputFileOptions]; otherwise builds the classic MediaStore
 * options. The caller closes [PendingStill.stream] in both save callbacks
 * (close is idempotent even if CameraX already closed it) and must prefer
 * [PendingStill.saveUri] over `outputFileResults.savedUri` (null for streams).
 */
internal data class PendingStill(
    val outputOptions: ImageCapture.OutputFileOptions,
    /** The URI the capture will land at (doc URI for SAF, null → use savedUri). */
    val saveUri: Uri?,
    val stream: OutputStream?,
    /** Display name used, for logging. */
    val displayName: String,
)

internal fun CameraViewModel.prepareStillSave(displayName: String): PendingStill? {
    // Refresh the fix at capture time: lastLocation is otherwise only read on screen
    // launch / settings toggle and can be stale or null (issue #731).
    updateLocation()
    val metadata = ImageCapture.Metadata().apply {
        if (locationEnabledMutable.value) location = lastLocation
        isReversedHorizontal = mirrorCaptures
    }
    val target = saveTargetMutable.value
    if (target is SaveTarget.SafTree) {
        val doc = SafDocuments.createImageDoc(app.contentResolver, target.treeUri, displayName)
        if (doc != null) {
            try {
                val stream = app.contentResolver.openOutputStream(doc)
                if (stream != null) {
                    val options = ImageCapture.OutputFileOptions.Builder(stream)
                        .setMetadata(metadata).build()
                    return PendingStill(options, doc, stream, displayName)
                }
                Log.status("CameraViewModel", "Could not open SAF doc for $displayName; using MediaStore")
            } catch (e: java.io.FileNotFoundException) {
                Log.status("CameraViewModel", "SAF still save failed for $displayName; using MediaStore", e)
            } catch (e: SecurityException) {
                Log.status("CameraViewModel", "SAF still save failed for $displayName; using MediaStore", e)
            }
        } else {
            Log.status("CameraViewModel", "SAF doc creation failed for $displayName; using MediaStore")
        }
        // Fall through to MediaStore so the shot is never lost.
    }
    val values = MediaStoreSaver.imageValues(displayName)
    val options = ImageCapture.OutputFileOptions.Builder(
        app.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values,
    ).setMetadata(metadata).build()
    return PendingStill(options, null, null, displayName)
}

internal fun PendingStill.closeStream() {
    try {
        stream?.close()
    } catch (_: Exception) {}
}

/** Resolve the final saved URI, preferring the pre-created SAF doc. */
internal fun PendingStill.resolveUri(results: ImageCapture.OutputFileResults): Uri? =
    saveUri ?: results.savedUri

/**
 * Saves re-encoded bytes/bitmap to the current target: SAF doc when set
 * (created under [displayName]), else classic MediaStore insert. Runs the
 * blocking I/O on the calling thread — invoke from Dispatchers.IO.
 */
internal fun CameraViewModel.saveStillBytes(displayName: String, bytes: ByteArray): Uri? =
    saveStillBytes(displayName, bytes, StillFormatRouting.mimeType(StillFormat.JPEG))

/** Same as [saveStillBytes] for pre-encoded bytes in a non-JPEG container. */
internal fun CameraViewModel.saveStillBytes(displayName: String, bytes: ByteArray, mimeType: String): Uri? {
    val target = saveTargetMutable.value
    if (target is SaveTarget.SafTree) {
        val doc = SafDocuments.createImageDoc(app.contentResolver, target.treeUri, displayName, mimeType)
        if (doc != null) {
            MediaStoreSaver.saveJpegBytesToUri(app.contentResolver, doc, bytes)?.let {
                scanSafDoc(doc)
                return it
            }
            Log.status("CameraViewModel", "SAF bytes save failed for $displayName; using MediaStore")
        }
    }
    return MediaStoreSaver.saveJpegBytes(
        app.contentResolver, MediaStoreSaver.imageValues(displayName, mimeType), bytes
    )
}

/** A saved processed still: where it landed and the container actually used. */
internal data class SavedStill(val uri: Uri, val format: StillFormat)

/**
 * Encodes a processed [bitmap] in the user's still format and saves it.
 * AVIF has no EXIF orientation tag (ravif exposes no EXIF API), so the
 * rotation is baked into the pixels first; JPEG keeps the bitmap as-is
 * and the caller stamps orientation via `writeCaptureExif` as before.
 * Falls back to JPEG bytes when the native AVIF encoder is unavailable.
 */
internal fun CameraViewModel.saveProcessedStill(bitmap: Bitmap, rotationDegrees: Int): SavedStill? {
    val encoded: EncodedStill = encode(bitmap, rotationDegrees)
    val name = StillFormatRouting.displayName(MediaStoreSaver.timestamp(), encoded.format)
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
