package com.vayunmathur.office.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
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

@Composable
fun OdfImageView(image: OdfImage, modifier: Modifier = Modifier) {
    val bitmap = remember(image.path, image.imageData.size) {
        if (image.imageData.isNotEmpty()) try { BitmapFactory.decodeByteArray(
            image.imageData,
            0,
            image.imageData.size) } catch (_: Exception) { null } else null
    }
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
                modifier.width((image.width * (160f / 96f)).coerceAtMost(700f).dp).aspectRatio(aspect)
            else modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.3f, 4f))
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
            return
        }
        val useExplicit = image.width > 0f && image.height > 0f
        if (useExplicit) {
            // Honor the frame's svg:width/height (px@96dpi -> dp), capped to a readable width. (A2)
            val widthDp = (image.width * (160f / 96f)).coerceAtMost(700f)
            val aspect = (image.width / image.height).coerceIn(0.1f, 10f)
            Image(
                bitmap = bitmap.asImageBitmap(), contentDescription = null,
                modifier = modifier.width(widthDp.dp).aspectRatio(aspect).then(rot).padding(vertical = 4.dp),
                contentScale = ContentScale.Fit, alpha = imgAlpha, colorFilter = imgFilter
            )
        } else {
            val aspect = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1.5f
            Image(
                bitmap = bitmap.asImageBitmap(), contentDescription = null,
                modifier = modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(
                    0.3f,
                    4f)).then(rot).padding(vertical = 4.dp),
                contentScale = ContentScale.Fit, alpha = imgAlpha, colorFilter = imgFilter
            )
        }
    } else {
        Box(modifier.fillMaxWidth().height(120.dp).padding(vertical = 4.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.image), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
