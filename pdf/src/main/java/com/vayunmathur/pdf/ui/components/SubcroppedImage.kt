package com.vayunmathur.pdf.ui.components

import android.graphics.Bitmap
import android.graphics.ImageDecoder
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
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.compose.AsyncImageState
import com.vayunmathur.pdf.data.CapturedImage
import com.vayunmathur.pdf.data.Quadrilateral
import com.vayunmathur.pdf.platform.warpQuadToBitmap
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

                val result = warpQuadToBitmap(bitmap, quad, targetWidth, targetHeight)
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
    val finalModifier = painterSize?.takeIf { it.width > 0f && it.height > 0f }?.let { size ->
        modifier.aspectRatio((size.width * crop.width) / (size.height * crop.height))
    } ?: modifier

    Box(finalModifier.clipToBounds()) {
        AsyncImage(
            model = image.uri,
            contentDescription = null,
            onState = { state ->
                if (state is AsyncImageState.Success) {
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
