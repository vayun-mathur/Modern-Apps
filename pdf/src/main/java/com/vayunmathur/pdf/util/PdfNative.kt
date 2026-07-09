package com.vayunmathur.pdf.util

/**
 * JNI bridge to the native Rust PDF renderer (`libpdf_render.so`, built from
 * `pdf/rust/`). Loads the library once; [isAvailable] is false if the native
 * lib is missing for the current ABI so the safe viewer can show a clean error
 * instead of crashing.
 *
 * All entry points are blocking and must be called off the main thread. Handles
 * returned by [openDocument] are opaque; pass 0 to mean "no document".
 */
object PdfNative {

    val isAvailable: Boolean =
        try {
            System.loadLibrary("pdf_render")
            android.util.Log.i("PdfNative", "libpdf_render loaded")
            true
        } catch (t: Throwable) {
            android.util.Log.e("PdfNative", "System.loadLibrary(pdf_render) failed", t)
            false
        }

    /**
     * Parse [data] (the raw PDF bytes) and return an opaque handle, or 0 on
     * parse failure or if the document is encrypted (v1 does not decrypt).
     */
    external fun openDocument(data: ByteArray): Long

    /** Number of pages in the document behind [handle], or 0 if unknown. */
    external fun getPageCount(handle: Long): Int

    /**
     * Render page [index] (0-based) into the serialized primitive buffer
     * consumed by [SafePdfParser], or `null` on any error.
     */
    external fun renderPage(handle: Long, index: Int): ByteArray?

    /** Release the document behind [handle]. Safe to call with 0. */
    external fun closeDocument(handle: Long)

    // --- Compose / merge ("cut and glue") ---------------------------------

    /** Create a new empty document; returns its handle. */
    external fun createEmptyDocument(): Long

    /** Append all pages of the PDF [data] to [handle]; returns pages added. */
    external fun appendPdf(handle: Long, data: ByteArray): Int

    /** Append a JPEG [jpeg] ([w]x[h]) as a new page; returns 1 on success. */
    external fun appendImagePage(handle: Long, jpeg: ByteArray, w: Int, h: Int): Int

    /** Move the page at [from] to index [to]. */
    external fun movePage(handle: Long, from: Int, to: Int): Boolean

    /** Remove the page at [index] from the page order. */
    external fun removePage(handle: Long, index: Int): Boolean

    /** Rotate the page at [index] by [delta] degrees. */
    external fun rotatePage(handle: Long, index: Int, delta: Int): Boolean

    /** Extract the page at [index] into a standalone one-page PDF (bytes), or null. */
    external fun extractPage(handle: Long, index: Int): ByteArray?

    /** Extract the document's visible text, or null. */
    external fun extractText(handle: Long): String?

    // --- Editing: annotations, forms, save ---------------------------------

    /** Serialized annotations on [page] for the overlay/hit-testing. */
    external fun listAnnotations(handle: Long, page: Int): ByteArray?

    /** Serialized AcroForm widget fields on [page]. */
    external fun listFormFields(handle: Long, page: Int): ByteArray?

    /** Add a FreeText annotation; returns its id (0 on failure). */
    external fun addTextAnnotation(
        handle: Long, page: Int,
        x0: Float, y0: Float, x1: Float, y1: Float,
        argb: Int, size: Float, text: String,
    ): Long

    external fun addHighlight(
        handle: Long, page: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int,
    ): Long

    /** [kind]: 0 underline, 1 strikeout, 2 squiggly. */
    external fun addTextMarkup(
        handle: Long, page: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, kind: Int,
    ): Long

    external fun addNote(
        handle: Long, page: Int, x: Float, y: Float, argb: Int, text: String,
    ): Long

    external fun addCallout(
        handle: Long, page: Int, ax: Float, ay: Float, bx: Float, by: Float,
        argb: Int, size: Float, text: String,
    ): Long

    external fun addRectAnnotation(
        handle: Long, page: Int,
        x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, lineWidth: Float, fill: Boolean,
    ): Long

    external fun addCircleAnnotation(
        handle: Long, page: Int,
        x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, lineWidth: Float, fill: Boolean,
    ): Long

    /** [pts] are flat page-space x,y pairs. [closed] draws a Polygon (fillable),
     * otherwise a PolyLine (stroked). */
    external fun addPolyAnnotation(
        handle: Long, page: Int, argb: Int, lineWidth: Float, fill: Boolean, closed: Boolean,
        pts: FloatArray,
    ): Long

    /** [pts] are flat page-space x,y pairs of one ink stroke. */
    external fun addInkAnnotation(
        handle: Long, page: Int, argb: Int, lineWidth: Float, pts: FloatArray,
    ): Long

    external fun addImageStamp(
        handle: Long, page: Int,
        x0: Float, y0: Float, x1: Float, y1: Float,
        imgW: Int, imgH: Int, jpeg: ByteArray,
    ): Long

    external fun updateAnnotationRect(
        handle: Long, page: Int, annotId: Long, x0: Float, y0: Float, x1: Float, y1: Float,
    ): Boolean

    external fun updateTextAnnotation(handle: Long, annotId: Long, text: String): Boolean

    external fun deleteAnnotation(handle: Long, page: Int, annotId: Long): Boolean

    /** Remove an annotation's page reference but keep the object (undo). */
    external fun detachAnnotation(handle: Long, page: Int, annotId: Long): Boolean

    /** Re-attach a previously detached annotation (redo / undo-delete). */
    external fun reattachAnnotation(handle: Long, page: Int, annotId: Long): Boolean

    /** Duplicate an annotation shifted by (dx,dy) page-space units; returns new id or 0. */
    external fun duplicateAnnotation(
        handle: Long, page: Int, annotId: Long, dx: Float, dy: Float,
    ): Long

    external fun setTextField(handle: Long, widgetId: Long, value: String): Boolean

    external fun setCheckbox(handle: Long, widgetId: Long, on: Boolean): Boolean

    /** Serialize the modified document to PDF bytes, or null on failure. */
    external fun saveDocument(handle: Long): ByteArray?

    /** Serialized document outline (bookmarks), or null. */
    external fun listOutline(handle: Long): ByteArray?

    /** Serialized case-insensitive search matches across all pages. */
    external fun searchDocument(handle: Long, query: String): ByteArray?

    /** Prebuild the search text index (call off the main thread). */
    external fun buildSearchIndex(handle: Long)
}
