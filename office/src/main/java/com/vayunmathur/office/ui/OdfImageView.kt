package com.vayunmathur.office.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.office.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun Modifier.rotateForDisplay(degrees: Float): Modifier {
    if (degrees == 0f) return this
    val norm = ((degrees % 360f) + 360f) % 360f
    val quarterTurn = norm in 45f..135f || norm in 225f..315f
    return if (!quarterTurn) this.rotate(degrees)
    else this
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.height, placeable.width) {
                placeable.place((placeable.height - placeable.width) / 2, (placeable.width - placeable.height) / 2)
            }
        }
        .rotate(degrees)
}

private fun OdfImage.alphaValue(): Float = (opacityPercent / 100f).coerceIn(0f, 1f)

private fun OdfImage.effectFilter(): ColorFilter? = when (colorMode) {
    "greyscale", "mono" -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    "watermark" -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        0.4f, 0f, 0f, 0f, 140f,
        0f, 0.4f, 0f, 0f, 140f,
        0f, 0f, 0.4f, 0f, 140f,
        0f, 0f, 0f, 1f, 0f
    )))
    else -> null
}

/**
 * Decode [data] down-sampled to roughly [reqWidthPx] x [reqHeightPx]: a
 * bounds-only first pass picks the largest power-of-two `inSampleSize` that
 * keeps both dimensions at or above the requested size, so a multi-megapixel
 * embedded photo never allocates a full-size bitmap for a small frame.
 * Returns null for empty/undecodable data, matching the previous behaviour.
 */
internal fun decodeSampledBitmap(data: ByteArray, reqWidthPx: Int, reqHeightPx: Int): Bitmap? {
    if (data.isEmpty()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val reqW = reqWidthPx.coerceAtLeast(1)
        val reqH = reqHeightPx.coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
    } catch (_: Exception) { null }
}

/** Decode state for [OdfImageView]: the placeholder label only appears once decoding has finished and failed. */
private sealed interface SampledDecode {
    data object Loading : SampledDecode
    data class Done(val bitmap: Bitmap?) : SampledDecode
}

@Composable
fun OdfImageView(image: OdfImage, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current.density
        val reqWidthPx = if (maxWidth == Dp.Infinity) 1024 else (maxWidth.value * density).toInt().coerceAtLeast(1)
        // Scrolling containers give an unbounded height; the width is the binding
        // constraint there (aspect is clamped below), so sample against it.
        val reqHeightPx = if (maxHeight == Dp.Infinity) reqWidthPx else (maxHeight.value * density).toInt().coerceAtLeast(1)
        val decoded by produceState<SampledDecode>(
            if (image.imageData.isEmpty()) SampledDecode.Done(null) else SampledDecode.Loading,
            image.path, image.imageData.size, reqWidthPx, reqHeightPx) {
            if (image.imageData.isNotEmpty()) {
                value = SampledDecode.Done(withContext(Dispatchers.IO) {
                    decodeSampledBitmap(image.imageData, reqWidthPx, reqHeightPx)
                })
            }
        }
        val bitmap = (decoded as? SampledDecode.Done)?.bitmap
        if (bitmap != null) {
            val rot = Modifier.rotateForDisplay(image.rotationDegrees)
            val imgAlpha = image.alphaValue()
            val imgFilter = image.effectFilter()
            val hasCrop =
                image.cropLeftPct > 0f || image.cropTopPct > 0f || image.cropRightPct > 0f || image.cropBottomPct > 0f
            if (hasCrop) {
                // Non-destructive crop: draw only the visible source rectangle, scaled to fill. (Phase 5)
                val bw = bitmap.width; val bh = bitmap.height
                val visW = (1f - image.cropLeftPct - image.cropRightPct).coerceIn(0.05f, 1f)
                val visH = (1f - image.cropTopPct - image.cropBottomPct).coerceIn(0.05f, 1f)
                val srcX = (image.cropLeftPct * bw).toInt().coerceIn(0, bw - 1)
                val srcY = (image.cropTopPct * bh).toInt().coerceIn(0, bh - 1)
                val srcW = (visW * bw).toInt().coerceIn(1, bw - srcX)
                val srcH = (visH * bh).toInt().coerceIn(1, bh - srcY)
                val aspect = (srcW.toFloat() / srcH).coerceIn(0.1f, 10f)
                val img = bitmap.asImageBitmap()
                val sized = if (image.width > 0f && image.height > 0f)
                    Modifier.width((image.width * (160f / 96f)).coerceAtMost(700f).dp).aspectRatio(aspect)
                else Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.3f, 4f))
                Canvas(sized.then(rot).padding(vertical = 4.dp)) {
                    drawImage(
                        image = img,
                        srcOffset = IntOffset(srcX, srcY),
                        srcSize = IntSize(srcW, srcH),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
                        alpha = imgAlpha,
                        colorFilter = imgFilter
                    )
                }
            } else if (image.width > 0f && image.height > 0f) {
                // Honor the frame's svg:width/height (px@96dpi -> dp), capped to a readable width. (A2)
                val widthDp = (image.width * (160f / 96f)).coerceAtMost(700f)
                val aspect = (image.width / image.height).coerceIn(0.1f, 10f)
                Image(
                    bitmap = bitmap.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.width(widthDp.dp).aspectRatio(aspect).then(rot).padding(vertical = 4.dp),
                    contentScale = ContentScale.Fit, alpha = imgAlpha, colorFilter = imgFilter
                )
            } else {
                val aspect = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1.5f
                Image(
                    bitmap = bitmap.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(
                        0.3f,
                        4f)).then(rot).padding(vertical = 4.dp),
                    contentScale = ContentScale.Fit, alpha = imgAlpha, colorFilter = imgFilter
                )
            }
        } else {
            // Placeholder box while decoding; the label appears once decoding
            // has finished, for empty/undecodable data (the previous error UI).
            Box(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 4.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (decoded is SampledDecode.Done) {
                    Text(stringResource(R.string.image), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
