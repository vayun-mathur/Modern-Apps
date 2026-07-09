package com.vayunmathur.pdf.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * A parsed PDF for the "safe" viewer, backed by the native Rust renderer.
 *
 * Reads the [Uri] bytes through the [Context]'s content resolver, hands them to
 * [PdfNative.openDocument], and exposes the [pageCount] plus a per-page
 * [renderPage]. Rendered pages are cached so scrolling back does not re-decode.
 * All native work happens on [Dispatchers.IO]; callers must [close] when done.
 */
class SafePdfDocument private constructor(
    private val handle: Long,
    val pageCount: Int,
) {
    private val cache = ConcurrentHashMap<Int, SafePdfPage>()

    /** Decode page [index] (0-based), or `null` if the native render fails. */
    suspend fun renderPage(index: Int): SafePdfPage? = withContext(Dispatchers.IO) {
        cache[index]?.let { return@withContext it }
        val bytes = PdfNative.renderPage(handle, index) ?: return@withContext null
        val page = SafePdfParser.parse(bytes)
        cache[index] = page
        page
    }

    /** Release native resources. Idempotent-safe to call once. */
    fun close() {
        PdfNative.closeDocument(handle)
    }

    private fun invalidate(index: Int) {
        cache.remove(index)
    }

    /** Annotations on [index] for the editing overlay. */
    suspend fun annotations(index: Int): List<SafeAnnotation> = withContext(Dispatchers.IO) {
        PdfNative.listAnnotations(handle, index)
            ?.let { SafePdfParser.parseAnnotations(it) } ?: emptyList()
    }

    /** AcroForm widget fields on [index]. */
    suspend fun formFields(index: Int): List<SafeFormField> = withContext(Dispatchers.IO) {
        PdfNative.listFormFields(handle, index)
            ?.let { SafePdfParser.parseFormFields(it) } ?: emptyList()
    }

    suspend fun addText(
        index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, size: Float, text: String,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addTextAnnotation(handle, index, x0, y0, x1, y1, argb, size, text)
            .also { invalidate(index) }
    }

    suspend fun addHighlight(
        index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addHighlight(handle, index, x0, y0, x1, y1, argb).also { invalidate(index) }
    }

    suspend fun addRect(
        index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, lineWidth: Float,
        fill: Boolean,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addRectAnnotation(handle, index, x0, y0, x1, y1, argb, lineWidth, fill)
            .also { invalidate(index) }
    }

    suspend fun addOval(
        index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, lineWidth: Float,
        fill: Boolean,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addCircleAnnotation(handle, index, x0, y0, x1, y1, argb, lineWidth, fill)
            .also { invalidate(index) }
    }

    /** [pts] are flat page-space x,y pairs. [closed] fills/closes the path. */
    suspend fun addPoly(
        index: Int, pts: FloatArray, argb: Int, lineWidth: Float, fill: Boolean, closed: Boolean,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addPolyAnnotation(handle, index, argb, lineWidth, fill, closed, pts)
            .also { invalidate(index) }
    }

    suspend fun addInk(
        index: Int, argb: Int, lineWidth: Float, pts: FloatArray,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addInkAnnotation(handle, index, argb, lineWidth, pts).also { invalidate(index) }
    }

    suspend fun addImageStamp(
        index: Int, x0: Float, y0: Float, x1: Float, y1: Float, imgW: Int, imgH: Int, jpeg: ByteArray,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addImageStamp(handle, index, x0, y0, x1, y1, imgW, imgH, jpeg)
            .also { invalidate(index) }
    }

    suspend fun moveAnnotation(
        index: Int, annotId: Long, x0: Float, y0: Float, x1: Float, y1: Float,
    ): Boolean = withContext(Dispatchers.IO) {
        PdfNative.updateAnnotationRect(handle, index, annotId, x0, y0, x1, y1).also { invalidate(index) }
    }

    suspend fun editText(index: Int, annotId: Long, text: String): Boolean =
        withContext(Dispatchers.IO) {
            PdfNative.updateTextAnnotation(handle, annotId, text).also { invalidate(index) }
        }

    suspend fun deleteAnnotation(index: Int, annotId: Long): Boolean = withContext(Dispatchers.IO) {
        PdfNative.deleteAnnotation(handle, index, annotId).also { invalidate(index) }
    }

    /** Detach (hide) an annotation, keeping it for undo. */
    suspend fun detachAnnotation(index: Int, annotId: Long): Boolean = withContext(Dispatchers.IO) {
        PdfNative.detachAnnotation(handle, index, annotId).also { invalidate(index) }
    }

    /** Re-attach a previously detached annotation. */
    suspend fun reattachAnnotation(index: Int, annotId: Long): Boolean = withContext(Dispatchers.IO) {
        PdfNative.reattachAnnotation(handle, index, annotId).also { invalidate(index) }
    }

    /** Duplicate an annotation shifted by (dx,dy); returns the new id (0 on failure). */
    suspend fun duplicateAnnotation(
        index: Int, annotId: Long, dx: Float, dy: Float,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.duplicateAnnotation(handle, index, annotId, dx, dy).also { invalidate(index) }
    }

    suspend fun setTextField(index: Int, widgetId: Long, value: String): Boolean =
        withContext(Dispatchers.IO) {
            PdfNative.setTextField(handle, widgetId, value).also { invalidate(index) }
        }

    suspend fun setCheckbox(index: Int, widgetId: Long, on: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            PdfNative.setCheckbox(handle, widgetId, on).also { invalidate(index) }
        }

    /** Serialize the (possibly edited) document to PDF bytes. */
    suspend fun save(): ByteArray? = withContext(Dispatchers.IO) { PdfNative.saveDocument(handle) }

    /** The document outline (bookmarks), empty if none. */
    suspend fun outline(): List<SafeOutlineItem> = withContext(Dispatchers.IO) {
        PdfNative.listOutline(handle)?.let { SafePdfParser.parseOutline(it) } ?: emptyList()
    }

    /** Case-insensitive full-text search across all pages. */
    suspend fun search(query: String): List<SafeSearchMatch> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList()
        else PdfNative.searchDocument(handle, query)?.let { SafePdfParser.parseSearchMatches(it) }
            ?: emptyList()
    }

    /** Prebuild the search text index so the first query is instant. */
    suspend fun prewarmSearch() = withContext(Dispatchers.IO) {
        PdfNative.buildSearchIndex(handle)
    }

    companion object {
        /**
         * Open [uri] as a safe PDF, or return `null` when the native lib is
         * unavailable, the bytes can't be read, or parsing fails (e.g. the PDF
         * is encrypted). Runs entirely off the main thread.
         */
        suspend fun open(context: Context, uri: Uri): SafePdfDocument? =
            withContext(Dispatchers.IO) {
                if (!PdfNative.isAvailable) return@withContext null
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: return@withContext null

                val handle = PdfNative.openDocument(bytes)
                if (handle == 0L) return@withContext null
                SafePdfDocument(handle, PdfNative.getPageCount(handle))
            }
    }
}
