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
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.Size as ImageSize
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.compose.AsyncImageState
import com.vayunmathur.library.image.decoders.BitmapDecoder
import com.vayunmathur.pdf.data.CapturedImage
import com.vayunmathur.pdf.data.Quadrilateral
import com.vayunmathur.pdf.platform.warpQuadToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * [thumbnailSizePx] is the capture list's hint: when set, the source is
 * decoded at that size (library/image's sized decode) instead of full
 * resolution, so an 80 dp list item never materialises the full photo.
 * Null (the main preview) keeps the full-resolution decode.
 */
@Composable
fun SubcroppedImage(
    image: CapturedImage,
    modifier: Modifier = Modifier,
    thumbnailSizePx: Int? = null
) {
    val quad = image.quadrilateral
    if (quad != null) {
        PerspectiveCroppedImage(image, quad, modifier, thumbnailSizePx)
    } else {
        BoundingBoxCroppedImage(image, modifier, thumbnailSizePx)
    }
}

@Composable
private fun PerspectiveCroppedImage(
    image: CapturedImage,
    quad: Quadrilateral,
    modifier: Modifier,
    thumbnailSizePx: Int? = null
) {
    val context = LocalContext.current
    var croppedBitmap by remember(image.uri, quad, thumbnailSizePx) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(image.uri, quad, thumbnailSizePx) {
        withContext(Dispatchers.Default) {
            try {
                val bitmap = if (thumbnailSizePx != null) {
                    // Sized decode from library/image: ImageDecoder samples
                    // inside the decoder, so the full-res bitmap never exists.
                    val request = ImageRequest.Builder(context)
                        .data(image.uri)
                        .size(ImageSize(thumbnailSizePx, thumbnailSizePx))
                        .build()
                    BitmapDecoder.decode(
                        ImageDecoder.createSource(context.contentResolver, image.uri),
                        request,
                        allowHardware = false,
                    ) ?: return@withContext
                } else {
                    val source = ImageDecoder.createSource(context.contentResolver, image.uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = false
                    }
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
    modifier: Modifier,
    thumbnailSizePx: Int? = null
) {
    val context = LocalContext.current
    var painterSize by remember(image.uri) { mutableStateOf<Size?>(null) }
    // Explicit sized request for list thumbnails; a plain Uri otherwise
    // (AsyncImage then derives its decode size from the layout constraints).
    val model: Any = remember(image.uri, thumbnailSizePx, context) {
        if (thumbnailSizePx != null) {
            ImageRequest.Builder(context)
                .data(image.uri)
                .size(ImageSize(thumbnailSizePx, thumbnailSizePx))
                .build()
        } else {
            image.uri
        }
    }

    val crop = image.cropRect ?: Rect(0f, 0f, 1f, 1f)
    val finalModifier = painterSize?.takeIf { it.width > 0f && it.height > 0f }?.let { size ->
        modifier.aspectRatio((size.width * crop.width) / (size.height * crop.height))
    } ?: modifier

    Box(finalModifier.clipToBounds()) {
        AsyncImage(
            model = model,
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
