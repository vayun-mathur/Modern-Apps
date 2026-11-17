package com.vayunmathur.pdf.platform

import com.vayunmathur.pdf.util.PdfNative

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A mutable PDF being composed in the "cut and glue" screen: starts empty and
 * grows as whole PDFs or images are appended; pages can be reordered/removed.
 * Backed by the native Rust document behind [handle]. Close is idempotent (P0 critical #5).
 */
class ComposePdfDocument private constructor(private var handle: Long) {
    @Volatile
    private var closed = false

    /** Current number of pages (cheap native call). */
    fun pageCount(): Int = if (closed || handle == 0L) 0 else PdfNative.getPageCount(handle)

    suspend fun renderPage(index: Int): SafePdfPage? = withContext(Dispatchers.IO) {
        if (closed || handle == 0L) return@withContext null
        PdfNative.renderPage(handle, index)?.let { SafePdfParser.parse(it) }
    }

    /** Append all pages of PDF [bytes]; returns pages added. */
    suspend fun appendPdf(bytes: ByteArray): Int = withContext(Dispatchers.IO) {
        if (closed || handle == 0L) return@withContext 0
        PdfNative.appendPdf(handle, bytes)
    }

    /** Append a JPEG image ([w]x[h]) as a new page; returns 1 on success. */
    suspend fun appendImage(jpeg: ByteArray, w: Int, h: Int): Int = withContext(Dispatchers.IO) {
        if (closed || handle == 0L) return@withContext 0
        PdfNative.appendImagePage(handle, jpeg, w, h)
    }

    suspend fun movePage(from: Int, to: Int): Boolean = withContext(Dispatchers.IO) {
        if (closed || handle == 0L) return@withContext false
        PdfNative.movePage(handle, from, to)
    }

    suspend fun removePage(index: Int): Boolean = withContext(Dispatchers.IO) {
        if (closed || handle == 0L) return@withContext false
        PdfNative.removePage(handle, index)
    }

    suspend fun save(): ByteArray? = withContext(Dispatchers.IO) {
        if (closed || handle == 0L) null else PdfNative.saveDocument(handle)
    }

    // P0 critical #5: close() not idempotent double-close crashes native — guard with closed flag + zero handle
    fun close() {
        synchronized(this) {
            if (closed || handle == 0L) return
            try { PdfNative.closeDocument(handle) } catch (_: Throwable) {}
            handle = 0L
            closed = true
        }
    }

    /** Shared constants and helpers for ComposePdfDocument. */
    companion object {
        fun create(): ComposePdfDocument = ComposePdfDocument(PdfNative.createEmptyDocument())
    }
}
