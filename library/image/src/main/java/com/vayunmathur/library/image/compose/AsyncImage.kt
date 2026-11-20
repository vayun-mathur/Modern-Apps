package com.vayunmathur.library.image.compose

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.ImageResult
import com.vayunmathur.library.image.Size

/**
 * Coil-like AsyncImage backed by our ImageLoader (HttpURLConnection + custom caches + SVG + Video).
 *
 * Supported model types (Any?): Uri, String URL, java.io.File, ByteArray, Bitmap, ImageRequest.
 * Mirrors signature used across codebase:
 * `AsyncImage(model, contentDescription, modifier, contentScale, colorFilter, onState)`
 *
 * Decode size: a request with no explicit size used to decode at up to
 * [com.vayunmathur.library.image.decoders.BitmapDecoder.MAX_ORIGINAL_DIMENSION]
 * px even into a 48.dp thumbnail, because most call sites pass a plain model and
 * rely on the default. Like Coil, the decode size is now derived from this
 * composable's layout constraints (see [deriveDecodeSize]); callers that pass an
 * explicit size — including [Size.Original] when they truly want the full
 * image — are left untouched.
 */
@Composable
fun AsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    colorFilter: ColorFilter? = null,
    alpha: Float = 1f,
    imageLoader: ImageLoader? = null,
    onState: ((AsyncImageState) -> Unit)? = null,
    alignment: Alignment = Alignment.Center,
) {
    val context = LocalContext.current
    val loader = imageLoader ?: remember(context) { ImageLoader.get(context) }

    val baseRequest = rememberImageRequest(model, context)

    // Fallback decode-size source for the case where the constraints are
    // unbounded (e.g. an infinite-width scroll row): the size this composable
    // was actually laid out at, known after the first layout pass.
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }

    BoxWithConstraints(
        modifier = modifier.onSizeChanged { measuredSize = it },
        contentAlignment = alignment,
    ) {
        val derivedSize = deriveDecodeSize(
            explicitSize = baseRequest?.size,
            constraintWidth = constraints.maxWidth,
            constraintHeight = constraints.maxHeight,
            measuredWidth = measuredSize.width,
            measuredHeight = measuredSize.height,
        )
        val request = remember(baseRequest, derivedSize) {
            when {
                baseRequest == null -> null
                // An explicit size (a real size, or Size.Original from a caller
                // that truly wants the full image) always wins.
                baseRequest.size != null -> baseRequest
                derivedSize != null -> baseRequest.copy(size = derivedSize)
                else -> baseRequest
            }
        }

        // Synchronous memory-cache probe. On a hit the bitmap is available before the
        // first frame, so a scrolled-in item paints immediately instead of showing an
        // empty box for a frame and re-running its crossfade over an image that was
        // already in memory.
        val seeded = rememberSeededBitmap(request, loader)

        val bitmapState = rememberAsyncBitmap(request, seeded, loader, onState)

        val bitmap = bitmapState.value

        // Crossfade alpha if requested
        val shouldCrossfade = request?.crossfade == true
        val targetAlpha = if (bitmap != null) alpha else 0f
        val animatedAlpha by animateFloatAsState(
            targetValue = if (shouldCrossfade) targetAlpha else alpha,
            label = "asyncImageCrossfade"
        )

        if (bitmap != null) {
            // Use Android-like scaling; honor colorFilter and contentScale
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize().let {
                    if (shouldCrossfade) it.alpha(animatedAlpha) else it
                },
                contentScale = contentScale,
                colorFilter = colorFilter,
                alignment = alignment,
            )
        }
    }
}

/**
 * The decode size for a request, or null to leave the request unsized.
 *
 * An [explicitSize] set by the caller (including [Size.Original]) is returned
 * unchanged. Otherwise the layout constraints win when both dimensions are
 * bounded ([constraintWidth]/[constraintHeight] are pixels;
 * [Int.MAX_VALUE] means unbounded), then the measured layout size, then null —
 * unsized, in which case the decoder still caps the longest edge at its
 * original-size limit rather than decoding without bound.
 */
internal fun deriveDecodeSize(
    explicitSize: Size?,
    constraintWidth: Int,
    constraintHeight: Int,
    measuredWidth: Int,
    measuredHeight: Int,
): Size? {
    if (explicitSize != null) return explicitSize
    if (constraintWidth in 1 until Int.MAX_VALUE && constraintHeight in 1 until Int.MAX_VALUE) {
        return Size(constraintWidth, constraintHeight)
    }
    if (measuredWidth > 0 && measuredHeight > 0) {
        return Size(measuredWidth, measuredHeight)
    }
    return null
}

@Composable
private fun rememberImageRequest(model: Any?, context: Context): ImageRequest? =
    remember(model) {
        when (model) {
            null -> null
            is ImageRequest -> model
            else -> ImageRequest.Builder(context).data(model).build()
        }
    }

@Composable
private fun rememberAsyncBitmap(
    request: ImageRequest?,
    seeded: Bitmap?,
    loader: ImageLoader,
    onState: ((AsyncImageState) -> Unit)?,
) = produceState<Bitmap?>(initialValue = seeded, key1 = request) {
    if (request == null) {
        value = null
        onState?.invoke(AsyncImageState.Empty)
        return@produceState
    }
    if (seeded != null) {
        // Served by the probe. `value` is assigned explicitly because
        // produceState only applies initialValue on first composition — on a
        // key change it still holds the previous request's bitmap.
        value = seeded
        onState?.invoke(AsyncImageState.Success(BitmapPainter(seeded.asImageBitmap())))
        return@produceState
    }
    onState?.invoke(AsyncImageState.Loading)
    when (val outcome = loadImageBitmap(request, loader)) {
        is ImageLoadOutcome.Ready -> {
            value = outcome.bitmap
            val painter = BitmapPainter(outcome.bitmap.asImageBitmap())
            onState?.invoke(AsyncImageState.Success(painter))
        }
        is ImageLoadOutcome.Failed -> {
            value = null
            onState?.invoke(AsyncImageState.Error(outcome.throwable))
        }
    }
}

@Composable
private fun rememberSeededBitmap(request: ImageRequest?, loader: ImageLoader): Bitmap? =
    remember(request) { request?.let { loader.peekMemoryCache(it) } }

private sealed interface ImageLoadOutcome {
    data class Ready(val bitmap: Bitmap) : ImageLoadOutcome
    data class Failed(val throwable: Throwable) : ImageLoadOutcome
}

private suspend fun loadImageBitmap(request: ImageRequest, loader: ImageLoader): ImageLoadOutcome {
    val result = runCatching { loader.execute(request) }
        .getOrElse { return ImageLoadOutcome.Failed(it) }
    return when (result) {
        is ImageResult.Success -> ImageLoadOutcome.Ready(result.bitmap)
        is ImageResult.Error -> ImageLoadOutcome.Failed(result.throwable)
    }
}

/**
 * Overload matching `coil.compose.AsyncImage(model: ImageRequest, ...)` convenience.
 */
@Composable
fun AsyncImage(
    model: ImageRequest?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    colorFilter: ColorFilter? = null,
    imageLoader: ImageLoader? = null,
    onState: ((AsyncImageState) -> Unit)? = null,
) {
    AsyncImage(
        model = model as Any?,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        colorFilter = colorFilter,
        imageLoader = imageLoader,
        onState = onState,
    )
}
