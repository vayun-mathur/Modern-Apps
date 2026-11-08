package com.vayunmathur.pdf.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.core.graphics.createBitmap
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.pdf.model.CapturedImage
import com.vayunmathur.pdf.model.Quadrilateral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Post-processing filter applied to each scanned page before export. */
enum class ScanFilter { NONE, GRAYSCALE, BW, CONTRAST }

suspend fun savePdfToUri(
    context: Context,
    images: List<CapturedImage>,
    targetUri: Uri,
    filter: ScanFilter = ScanFilter.NONE,
    addOcr: Boolean = false,
): Boolean = withContext(Dispatchers.IO) {
    val pdfDocument = PdfDocument()
    val ocr = if (addOcr) OcrEngine(context).takeIf { it.isAvailable() } else null
    try {
        for ((index, capturedImage) in images.withIndex()) {
            renderImagePage(context, pdfDocument, capturedImage, index, filter, ocr)
        }

        context.contentResolver.openFileDescriptor(targetUri, "w")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { fos -> pdfDocument.writeTo(fos) }
        }
        true
    } catch (expected: java.io.IOException) {
        Log.error("PdfExporter", "Failed to save PDF", expected)
        false
    } catch (expected: SecurityException) {
        Log.error("PdfExporter", "Failed to save PDF", expected)
        false
    } catch (expected: IllegalStateException) {
        Log.error("PdfExporter", "Failed to save PDF", expected)
        false
    } finally {
        pdfDocument.close()
        ocr?.close()
    }
}

private const val A4_LONG_SIDE_PT = 842f
private const val MIN_PAGE_DIM = 1

private suspend fun renderImagePage(
    context: Context,
    pdfDocument: PdfDocument,
    capturedImage: CapturedImage,
    index: Int,
    filter: ScanFilter,
    ocr: OcrEngine?,
) {
    val uri = capturedImage.uri
    try {
        val bitmap = decodeSource(context, uri) ?: return
        val pageSize = pageSize(bitmap, capturedImage)
        val targetWidth = pageSize.width
        val targetHeight = pageSize.height
        val pageBitmap = createBitmap(targetWidth, targetHeight)
        val pageCanvas = Canvas(pageBitmap)
        pageCanvas.drawColor(Color.WHITE)
        drawCropped(bitmap, capturedImage, pageCanvas, targetWidth, targetHeight, pageSize.scale)
        bitmap.recycle()
        applyScanFilter(pageBitmap, filter)

        val pageInfo = PdfDocument.PageInfo.Builder(targetWidth, targetHeight, index + 1).create()
        val page = pdfDocument.startPage(pageInfo)
        page.canvas.drawBitmap(pageBitmap, 0f, 0f, null)

        if (ocr != null) {
            drawOcrTextLayer(page.canvas, ocr.recognizeDetailed(pageBitmap))
        }

        pdfDocument.finishPage(page)
        pageBitmap.recycle()
    } catch (expected: java.io.IOException) {
        Log.error("PdfExporter", "Error processing image $uri", expected)
    } catch (expected: SecurityException) {
        Log.error("PdfExporter", "Error processing image $uri", expected)
    } catch (expected: IllegalStateException) {
        Log.error("PdfExporter", "Error processing image $uri", expected)
    }
}

private fun decodeSource(context: Context, uri: Uri): Bitmap? {
    return try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (expected: java.io.IOException) {
        Log.error("PdfExporter", "Error decoding $uri", expected)
        null
    } catch (expected: SecurityException) {
        Log.error("PdfExporter", "Error decoding $uri", expected)
        null
    }
}

private data class PageSize(val width: Int, val height: Int, val scale: Float)

private fun pageSize(bitmap: Bitmap, capturedImage: CapturedImage): PageSize {
    val crop = capturedImage.cropRect
    val quadrilateral = capturedImage.quadrilateral
    val (cropWidth, cropHeight) = when {
        quadrilateral != null -> {
            val bounds = quadrilateral.toBoundingRect()
            bitmap.width * bounds.width to bitmap.height * bounds.height
        }
        crop != null -> bitmap.width * crop.width to bitmap.height * crop.height
        else -> bitmap.width.toFloat() to bitmap.height.toFloat()
    }

    val scale = A4_LONG_SIDE_PT / maxOf(cropWidth, cropHeight)
    val targetWidth = (cropWidth * scale).toInt().coerceAtLeast(MIN_PAGE_DIM)
    val targetHeight = (cropHeight * scale).toInt().coerceAtLeast(MIN_PAGE_DIM)
    return PageSize(targetWidth, targetHeight, scale)
}

private fun drawCropped(
    bitmap: Bitmap,
    capturedImage: CapturedImage,
    pageCanvas: Canvas,
    targetWidth: Int,
    targetHeight: Int,
    drawScale: Float,
) {
    val crop = capturedImage.cropRect
    val quadrilateral = capturedImage.quadrilateral
    when {
        quadrilateral != null -> {
            val warped = warpQuadToBitmap(bitmap, quadrilateral, targetWidth, targetHeight)
            pageCanvas.drawBitmap(warped, 0f, 0f, null)
            warped.recycle()
        }
        crop != null -> {
            val srcRect = android.graphics.Rect(
                (crop.left * bitmap.width).roundToInt(),
                (crop.top * bitmap.height).roundToInt(),
                (crop.right * bitmap.width).roundToInt(),
                (crop.bottom * bitmap.height).roundToInt(),
            )
            val dst = android.graphics.Rect(0, 0, targetWidth, targetHeight)
            pageCanvas.drawBitmap(bitmap, srcRect, dst, null)
        }
        else -> {
            val matrix = Matrix()
            matrix.postScale(drawScale, drawScale)
            pageCanvas.drawBitmap(bitmap, matrix, null)
        }
    }
}

/** Apply a scan [filter] to [bmp] in place (via a filtered self-copy). */
private fun applyScanFilter(bmp: Bitmap, filter: ScanFilter) {
    if (filter == ScanFilter.NONE) return
    val matrix = when (filter) {
        ScanFilter.GRAYSCALE, ScanFilter.BW -> ColorMatrix().apply { setSaturation(0f) }
        ScanFilter.CONTRAST -> ColorMatrix(CONTRAST_MATRIX)
        ScanFilter.NONE -> return
    }
    if (filter == ScanFilter.BW) {
        // Strong contrast after desaturation approximates a bilevel scan.
        matrix.postConcat(ColorMatrix(BW_MATRIX))
    }
    val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
    Canvas(bmp).drawBitmap(copy, 0f, 0f, paint)
    copy.recycle()
}

private val CONTRAST_MATRIX = floatArrayOf(
    1.5f, 0f, 0f, 0f, -50f,
    0f, 1.5f, 0f, 0f, -50f,
    0f, 0f, 1.5f, 0f, -50f,
    0f, 0f, 0f, 1f, 0f,
)

private val BW_MATRIX = floatArrayOf(
    4f, 0f, 0f, 0f, -430f,
    0f, 4f, 0f, 0f, -430f,
    0f, 0f, 4f, 0f, -430f,
    0f, 0f, 0f, 1f, 0f,
)

/** Draw an invisible (transparent) OCR text layer so scans are selectable/searchable. */
private fun drawOcrTextLayer(canvas: Canvas, result: OcrEngine.OcrResult) {
    val paint = Paint().apply { color = Color.argb(0, 0, 0, 0) }
    val matrix = Matrix()
    for (b in result.boxes) {
        drawOcrBox(canvas, paint, matrix, b.text, b.corners)
    }
}

private const val MIN_BOX_DIM = 1f
private const val TEXT_HEIGHT_FRACTION = 0.8f
private const val BASELINE_FRACTION = 0.15f
private const val QUAD_POINTS = 4

private fun drawOcrBox(
    canvas: Canvas,
    paint: Paint,
    matrix: Matrix,
    text: String,
    corners: List<OcrEngine.Corner>,
) {
    if (text.isBlank()) return
    val c = corners
    // The run's own width and height, so a skewed scan's text layer follows
    // the text rather than its bounding box.
    val bw = hypot(c[1].x - c[0].x, c[1].y - c[0].y)
    val bh = hypot(c[3].x - c[0].x, c[3].y - c[0].y)
    if (bw <= MIN_BOX_DIM || bh <= MIN_BOX_DIM) return
    paint.textScaleX = 1f
    paint.textSize = bh * TEXT_HEIGHT_FRACTION
    val measured = paint.measureText(text)
    if (measured > 0f) paint.textScaleX = bw / measured
    val angle = atan2(c[1].y - c[0].y, c[1].x - c[0].x)
    matrix.setRotate(Math.toDegrees(angle.toDouble()).toFloat())
    matrix.postTranslate(c[0].x, c[0].y)
    canvas.save()
    canvas.concat(matrix)
    canvas.drawText(text, 0f, bh - bh * BASELINE_FRACTION, paint)
    canvas.restore()
}

/**
 * Perspective-warps [quad] (normalized corners) out of [src] into a new [width]x[height] bitmap.
 * Falls back to a bounding-box crop when the perspective matrix is degenerate. Caller owns the result.
 */
fun warpQuadToBitmap(src: Bitmap, quad: Quadrilateral, width: Int, height: Int): Bitmap {
    val targetWidth = width.coerceAtLeast(1)
    val targetHeight = height.coerceAtLeast(1)
    val srcPoints = quad.toSrcPoints(src.width, src.height)
    val dstPoints = floatArrayOf(
        0f, 0f,
        targetWidth.toFloat(), 0f,
        targetWidth.toFloat(), targetHeight.toFloat(),
        0f, targetHeight.toFloat()
    )
    val matrix = Matrix()
    return if (matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, QUAD_POINTS)) {
        createBitmap(targetWidth, targetHeight).also {
            Canvas(it).drawBitmap(src, matrix, null)
        }
    } else {
        val bounds = quad.toBoundingRect()
        val left = (bounds.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val top = (bounds.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val w = targetWidth.coerceAtMost(src.width - left)
        val h = targetHeight.coerceAtMost(src.height - top)
        // The subset shares the source's pixel storage, so hand back an
        // independent copy: every caller recycles the source right after this
        // returns (and the result later), which would otherwise free pixels
        // the returned bitmap still points at.
        val subset = Bitmap.createBitmap(src, left, top, w, h)
        subset.copy(Bitmap.Config.ARGB_8888, true).also { subset.recycle() }
    }
}
