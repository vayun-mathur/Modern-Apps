package com.vayunmathur.photos.util

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.vayunmathur.library.log.Log
import androidx.core.net.toUri
import com.vayunmathur.library.ink.SerializedStroke
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TextElement
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Save/export flow for the photo editor, split out of [PhotoEditViewModel]
 * so neither class exceeds the function-count cap. Reads the document through
 * [PhotoEditViewModel] and funnels completion through the permission-request
 * state the UI collects.
 */
class PhotoEditSaver(private val vm: PhotoEditViewModel) {

    private val _writePermissionRequest = MutableStateFlow<IntentSender?>(null)
    val writePermissionRequest: StateFlow<IntentSender?> = _writePermissionRequest

    private var pendingSaveBytes: ByteArray? = null
    private var pendingSaveUri: Uri? = null
    private var pendingSaveOnComplete: (() -> Unit)? = null

    fun savePhoto(
        photo: Photo,
        asCopy: Boolean,
        strokes: List<SerializedStroke>,
        texts: List<TextElement>,
        viewportWidth: Float,
        viewportHeight: Float,
        format: ExportFormat = ExportFormat.Jpeg,
        onComplete: () -> Unit,
    ) {
        val ctx: Context = vm.appContext
        val doc = vm.document.value
        vm.editScope.launch {
            val result = withContext(Dispatchers.Default) {
                val composite = vm.compositor.composite(doc, Int.MAX_VALUE)
                bakeOverlays(composite, strokes, texts, viewportWidth, viewportHeight)
                writeBitmap(ctx, photo, composite, asCopy, format)
            }
            when (result) {
                is WriteResult.Success -> onComplete()
                is WriteResult.NeedsPermission -> {
                    pendingSaveBytes = result.jpegBytes
                    pendingSaveUri = result.uri
                    pendingSaveOnComplete = onComplete
                    _writePermissionRequest.value = result.intentSender
                }
                is WriteResult.Error -> {
                    Log.error(TAG, "Save failed", result.exception)
                    onComplete()
                }
            }
        }
    }

    private fun bakeOverlays(
        target: Bitmap,
        strokes: List<SerializedStroke>,
        texts: List<TextElement>,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        if (strokes.isEmpty() && texts.isEmpty()) return
        val canvas = android.graphics.Canvas(target)
        if (strokes.isNotEmpty() && viewportWidth > 0f && viewportHeight > 0f) {
            canvas.drawSerializedStrokes(strokes, viewportWidth, viewportHeight, target.width, target.height)
        }
        texts.forEach { canvas.drawTextElement(it, target.width, target.height, viewportWidth) }
    }

    fun onWritePermissionGranted() {
        val bytes = pendingSaveBytes ?: return
        val uri = pendingSaveUri ?: return
        val onComplete = pendingSaveOnComplete ?: return
        pendingSaveBytes = null; pendingSaveUri = null; pendingSaveOnComplete = null
        _writePermissionRequest.value = null
        val ctx: Context = vm.appContext
        vm.editScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    val resolver = ctx.contentResolver
                    resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                        ?: throw IllegalStateException("openOutputStream returned null after permission grant")
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                        put(MediaStore.Images.Media.SIZE, bytes.size.toLong())
                    }
                    resolver.update(uri, values, null, null)
                } catch (e: IOException) {
                    Log.error(TAG, "Overwrite FAILED after permission grant", e)
                } catch (e: SecurityException) {
                    Log.error(TAG, "Overwrite FAILED after permission grant", e)
                }
            }
            onComplete()
        }
    }

    fun onWritePermissionDenied() {
        pendingSaveBytes = null; pendingSaveUri = null; pendingSaveOnComplete = null
        _writePermissionRequest.value = null
    }

    private sealed class WriteResult {
        data object Success : WriteResult()
        data class NeedsPermission(
            val intentSender: IntentSender,
            val jpegBytes: ByteArray,
            val uri: Uri,
        ) : WriteResult()
        data class Error(val exception: Exception) : WriteResult()
    }

    private fun writeBitmap(
        context: Context,
        photo: Photo,
        bitmap: Bitmap,
        asCopy: Boolean,
        format: ExportFormat,
    ): WriteResult {
        val resolver = context.contentResolver
        val nowSeconds = System.currentTimeMillis() / 1000
        if (asCopy) {
            val baseName = photo.name.substringBeforeLast('.')
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Edited_$baseName.${format.ext}")
                put(MediaStore.Images.Media.MIME_TYPE, format.mime)
                put(MediaStore.Images.Media.DATE_MODIFIED, nowSeconds)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(format.compress, format.quality, out)
                }
            }
            return WriteResult.Success
        }

        val uri = photo.uri.toUri()
        val bytes = ByteArrayOutputStream().also {
            bitmap.compress(format.compress, format.quality, it)
        }.toByteArray()
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DATE_MODIFIED, nowSeconds)
                put(MediaStore.Images.Media.SIZE, bytes.size.toLong())
            }
            resolver.update(uri, values, null, null)
            return WriteResult.Success
        } catch (e: IOException) {
            Log.debug(TAG, "Direct write failed, falling back to createWriteRequest", e)
        } catch (e: SecurityException) {
            Log.debug(TAG, "Direct write failed, falling back to createWriteRequest", e)
        }
        val pendingIntent = MediaStore.createWriteRequest(resolver, listOf(uri))
        return WriteResult.NeedsPermission(pendingIntent.intentSender, bytes, uri)
    }

    /** Shared constants and helpers for PhotoEditSaver. */
    companion object {
        private const val TAG = "PhotoEditSaver"
    }
}
