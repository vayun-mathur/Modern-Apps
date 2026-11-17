package com.vayunmathur.pdf.platform

import com.vayunmathur.pdf.util.PdfNative

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Annotation, form-field and redaction edits for [SafePdfDocument].
 *
 * Extracted so the document class stays under detekt's TooManyFunctions cap.
 * Every op delegates to [PdfNative] on IO and invalidates the rendered page
 * through [SafePdfDocument.invalidatePage].
 */

suspend fun SafePdfDocument.addText(
    index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, size: Float, text: String,
): Long = withContext(Dispatchers.IO) {
    PdfNative.addTextAnnotation(documentHandle, index, x0, y0, x1, y1, argb, size, text)
        .also { invalidatePage(index) }
}

suspend fun SafePdfDocument.addHighlight(
    index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int,
): Long = withContext(Dispatchers.IO) {
    PdfNative.addHighlight(documentHandle, index, x0, y0, x1, y1, argb).also { invalidatePage(index) }
}

/** [kind]: 0 underline, 1 strikeout, 2 squiggly. */
suspend fun SafePdfDocument.addTextMarkup(
    index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, kind: Int,
): Long = withContext(Dispatchers.IO) {
    PdfNative.addTextMarkup(documentHandle, index, x0, y0, x1, y1, argb, kind).also { invalidatePage(index) }
}

suspend fun SafePdfDocument.addNote(
    index: Int, x: Float, y: Float, argb: Int, text: String,
): Long = withContext(Dispatchers.IO) {
    PdfNative.addNote(documentHandle, index, x, y, argb, text).also { invalidatePage(index) }
}

suspend fun SafePdfDocument.addCallout(
    index: Int, ax: Float, ay: Float, bx: Float, by: Float, argb: Int, size: Float, text: String,
): Long = withContext(Dispatchers.IO) {
    PdfNative.addCallout(documentHandle, index, ax, ay, bx, by, argb, size, text).also { invalidatePage(index) }
}

suspend fun SafePdfDocument.addRect(
    index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, lineWidth: Float,
    fill: Boolean,
): Long = withContext(Dispatchers.IO) {
    PdfNative.addRectAnnotation(documentHandle, index, x0, y0, x1, y1, argb, lineWidth, fill)
        .also { invalidatePage(index) }
}

suspend fun SafePdfDocument.addOval(
    index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, lineWidth: Float,
    fill: Boolean,
): Long = withContext(Dispatchers.IO) {
    PdfNative.addCircleAnnotation(documentHandle, index, x0, y0, x1, y1, argb, lineWidth, fill)
        .also { invalidatePage(index) }
}

/** [pts] are flat page-space x,y pairs. [closed] fills/closes the path. */
suspend fun SafePdfDocument.addPoly(
    index: Int, pts: FloatArray, argb: Int, lineWidth: Float, fill: Boolean, closed: Boolean,
): Long = withContext(Dispatchers.IO) {
    PdfNative.addPolyAnnotation(documentHandle, index, argb, lineWidth, fill, closed, pts)
        .also { invalidatePage(index) }
}

suspend fun SafePdfDocument.addInk(
    index: Int, argb: Int, lineWidth: Float, pts: FloatArray,
): Long = withContext(Dispatchers.IO) {
    PdfNative.addInkAnnotation(documentHandle, index, argb, lineWidth, pts).also { invalidatePage(index) }
}

suspend fun SafePdfDocument.addImageStamp(
    index: Int, x0: Float, y0: Float, x1: Float, y1: Float, imgW: Int, imgH: Int, jpeg: ByteArray,
): Long = withContext(Dispatchers.IO) {
    PdfNative.addImageStamp(documentHandle, index, x0, y0, x1, y1, imgW, imgH, jpeg)
        .also { invalidatePage(index) }
}

suspend fun SafePdfDocument.moveAnnotation(
    index: Int, annotId: Long, x0: Float, y0: Float, x1: Float, y1: Float,
): Boolean = withContext(Dispatchers.IO) {
    PdfNative.updateAnnotationRect(documentHandle, index, annotId, x0, y0, x1, y1)
        .also { invalidatePage(index) }
}

suspend fun SafePdfDocument.editText(index: Int, annotId: Long, text: String): Boolean =
    withContext(Dispatchers.IO) {
        PdfNative.updateTextAnnotation(documentHandle, annotId, text).also { invalidatePage(index) }
    }

suspend fun SafePdfDocument.deleteAnnotation(index: Int, annotId: Long): Boolean = withContext(Dispatchers.IO) {
    PdfNative.deleteAnnotation(documentHandle, index, annotId).also { invalidatePage(index) }
}

/** Detach (hide) an annotation, keeping it for undo. */
suspend fun SafePdfDocument.detachAnnotation(index: Int, annotId: Long): Boolean = withContext(Dispatchers.IO) {
    PdfNative.detachAnnotation(documentHandle, index, annotId).also { invalidatePage(index) }
}

/** Re-attach a previously detached annotation. */
suspend fun SafePdfDocument.reattachAnnotation(index: Int, annotId: Long): Boolean = withContext(Dispatchers.IO) {
    PdfNative.reattachAnnotation(documentHandle, index, annotId).also { invalidatePage(index) }
}

/** Duplicate an annotation shifted by (dx,dy); returns the new id (0 on failure). */
suspend fun SafePdfDocument.duplicateAnnotation(
    index: Int, annotId: Long, dx: Float, dy: Float,
): Long = withContext(Dispatchers.IO) {
    PdfNative.duplicateAnnotation(documentHandle, index, annotId, dx, dy).also { invalidatePage(index) }
}

suspend fun SafePdfDocument.setTextField(index: Int, widgetId: Long, value: String): Boolean =
    withContext(Dispatchers.IO) {
        PdfNative.setTextField(documentHandle, widgetId, value).also { invalidatePage(index) }
    }

suspend fun SafePdfDocument.setCheckbox(index: Int, widgetId: Long, on: Boolean): Boolean =
    withContext(Dispatchers.IO) {
        PdfNative.setCheckbox(documentHandle, widgetId, on).also { invalidatePage(index) }
    }

suspend fun SafePdfDocument.setChoiceField(index: Int, widgetId: Long, value: String): Boolean =
    withContext(Dispatchers.IO) {
        PdfNative.setChoiceField(documentHandle, widgetId, value).also { invalidatePage(index) }
    }

/** Add a redaction annotation over the rect; returns id (0 on failure). */
suspend fun SafePdfDocument.addRedaction(
    index: Int, x0: Float, y0: Float, x1: Float, y1: Float,
): Long = withContext(Dispatchers.IO) {
    PdfNative.addRedaction(documentHandle, index, x0, y0, x1, y1).also { invalidatePage(index) }
}
