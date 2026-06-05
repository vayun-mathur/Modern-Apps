package com.vayunmathur.pdf.util

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import com.vayunmathur.pdf.model.CapturedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.math.roundToInt

suspend fun savePdfToUri(context: Context, images: List<CapturedImage>, targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
    val pdfDocument = PdfDocument()
    try {
        images.forEachIndexed { index, capturedImage ->
            val uri = capturedImage.uri
            try {
                val crop = capturedImage.cropRect
                val quadrilateral = capturedImage.quadrilateral
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                
                // Determine crop dimensions
                val (cropWidth, cropHeight) = when {
                    quadrilateral != null -> {
                        // For quadrilateral, use bounding box dimensions
                        val bounds = quadrilateral.toBoundingRect()
                        bitmap.width * bounds.width to bitmap.height * bounds.height
                    }
                    crop != null -> bitmap.width * crop.width to bitmap.height * crop.height
                    else -> bitmap.width.toFloat() to bitmap.height.toFloat()
                }

                // Scale the image so its longest side matches the longest side of A4 (842 points).
                val a4LongSide = 842f
                val scale = if (cropWidth > cropHeight) {
                    a4LongSide / cropWidth
                } else {
                    a4LongSide / cropHeight
                }
                
                val targetWidth = (cropWidth * scale).toInt().coerceAtLeast(1)
                val targetHeight = (cropHeight * scale).toInt().coerceAtLeast(1)

                val pageInfo = PdfDocument.PageInfo.Builder(targetWidth, targetHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                when {
                    quadrilateral != null -> {
                        // Apply perspective transform using Matrix.setPolyToPoly
                        // This maps the 4 corners of the quadrilateral to the 4 corners of the output rectangle
                        val srcPoints = floatArrayOf(
                            quadrilateral.topLeft.x * bitmap.width,
                            quadrilateral.topLeft.y * bitmap.height,
                            quadrilateral.topRight.x * bitmap.width,
                            quadrilateral.topRight.y * bitmap.height,
                            quadrilateral.bottomRight.x * bitmap.width,
                            quadrilateral.bottomRight.y * bitmap.height,
                            quadrilateral.bottomLeft.x * bitmap.width,
                            quadrilateral.bottomLeft.y * bitmap.height
                        )
                        val dstPoints = floatArrayOf(
                            0f, 0f,
                            targetWidth.toFloat(), 0f,
                            targetWidth.toFloat(), targetHeight.toFloat(),
                            0f, targetHeight.toFloat()
                        )
                        val matrix = Matrix()
                        val success = matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)
                        if (!success) {
                            Log.w("PdfExporter", "setPolyToPoly failed, falling back to bounding rect crop")
                            // Fallback to bounding rect if perspective transform fails
                            val bounds = quadrilateral.toBoundingRect()
                            val srcRect = android.graphics.Rect(
                                (bounds.left * bitmap.width).roundToInt(),
                                (bounds.top * bitmap.height).roundToInt(),
                                (bounds.right * bitmap.width).roundToInt(),
                                (bounds.bottom * bitmap.height).roundToInt()
                            )
                            val dstRect = android.graphics.Rect(0, 0, targetWidth, targetHeight)
                            page.canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                        } else {
                            page.canvas.drawBitmap(bitmap, matrix, null)
                        }
                    }
                    crop != null -> {
                        val srcRect = android.graphics.Rect(
                            (crop.left * bitmap.width).roundToInt(),
                            (crop.top * bitmap.height).roundToInt(),
                            (crop.right * bitmap.width).roundToInt(),
                            (crop.bottom * bitmap.height).roundToInt()
                        )
                        val dstRect = android.graphics.Rect(0, 0, targetWidth, targetHeight)
                        page.canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                    }
                    else -> {
                        val matrix = Matrix()
                        matrix.postScale(scale, scale)
                        page.canvas.drawBitmap(bitmap, matrix, null)
                    }
                }

                pdfDocument.finishPage(page)
                bitmap.recycle()
            } catch (e: Exception) {
                Log.e("PdfExporter", "Error processing image $uri", e)
            }
        }

        context.contentResolver.openFileDescriptor(targetUri, "w")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { fos ->
                pdfDocument.writeTo(fos)
            }
        }
        true
    } catch (e: Exception) {
        Log.e("PdfExporter", "Failed to save PDF", e)
        false
    } finally {
        pdfDocument.close()
    }
}
