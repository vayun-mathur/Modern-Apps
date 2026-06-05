package com.vayunmathur.pdf.ui.components

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.vayunmathur.pdf.model.CapturedImage
import com.vayunmathur.pdf.model.Quadrilateral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SubcroppedImage(
    image: CapturedImage,
    modifier: Modifier = Modifier
) {
    val quad = image.quadrilateral
    if (quad != null) {
        PerspectiveCroppedImage(image, quad, modifier)
    } else {
        BoundingBoxCroppedImage(image, modifier)
    }
}

@Composable
private fun PerspectiveCroppedImage(
    image: CapturedImage,
    quad: Quadrilateral,
    modifier: Modifier
) {
    val context = LocalContext.current
    var croppedBitmap by remember(image.uri, quad) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(image.uri, quad) {
        withContext(Dispatchers.Default) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, image.uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }

                val bounds = quad.toBoundingRect()
                val targetWidth = (bounds.width * bitmap.width).roundToInt().coerceAtLeast(1)
                val targetHeight = (bounds.height * bitmap.height).roundToInt().coerceAtLeast(1)

                val srcPoints = floatArrayOf(
                    quad.topLeft.x * bitmap.width, quad.topLeft.y * bitmap.height,
                    quad.topRight.x * bitmap.width, quad.topRight.y * bitmap.height,
                    quad.bottomRight.x * bitmap.width, quad.bottomRight.y * bitmap.height,
                    quad.bottomLeft.x * bitmap.width, quad.bottomLeft.y * bitmap.height
                )
                val dstPoints = floatArrayOf(
                    0f, 0f,
                    targetWidth.toFloat(), 0f,
                    targetWidth.toFloat(), targetHeight.toFloat(),
                    0f, targetHeight.toFloat()
                )
                val matrix = Matrix()
                val result = if (matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)) {
                    val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    android.graphics.Canvas(out).drawBitmap(bitmap, matrix, null)
                    out
                } else {
                    // Fallback: bounding box crop
                    val left = (bounds.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
                    val top = (bounds.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
                    val w = targetWidth.coerceAtMost(bitmap.width - left)
                    val h = targetHeight.coerceAtMost(bitmap.height - top)
                    Bitmap.createBitmap(bitmap, left, top, w, h)
                }
                bitmap.recycle()
                croppedBitmap = result
            } catch (_: Exception) {}
        }
    }

    croppedBitmap?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun BoundingBoxCroppedImage(
    image: CapturedImage,
    modifier: Modifier
) {
    var painterSize by remember(image.uri) { mutableStateOf<Size?>(null) }

    val crop = image.cropRect ?: Rect(0f, 0f, 1f, 1f)
    val finalModifier = if (painterSize != null && painterSize!!.width > 0f && painterSize!!.height > 0f) {
        val aspect = (painterSize!!.width * crop.width) / (painterSize!!.height * crop.height)
        modifier.aspectRatio(aspect)
    } else {
        modifier
    }

    Box(finalModifier.clipToBounds()) {
        AsyncImage(
            model = image.uri,
            contentDescription = null,
            onState = { state ->
                if (state is AsyncImagePainter.State.Success) {
                    painterSize = state.painter.intrinsicSize
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val sX = 1f / crop.width
                    val sY = 1f / crop.height
                    scaleX = sX
                    scaleY = sY
                    translationX = -crop.left * size.width * sX
                    translationY = -crop.top * size.height * sY
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            contentScale = ContentScale.FillBounds
        )
    }
}
