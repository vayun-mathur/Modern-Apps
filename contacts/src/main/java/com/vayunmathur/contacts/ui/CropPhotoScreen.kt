package com.vayunmathur.contacts.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.core.net.toUri
import androidx.core.graphics.scale
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropPhotoScreen(
    uri: String,
    onCropComplete: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    var cropOffset by remember { mutableStateOf(Offset.Zero) }
    var cropSize by remember { mutableFloatStateOf(0f) }
    var imgDisplayOffset by remember { mutableStateOf(Offset.Zero) }
    var imgDisplayW by remember { mutableFloatStateOf(0f) }
    var imgDisplayH by remember { mutableFloatStateOf(0f) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val parsed = uri.toUri()
                // First pass: decode bounds to determine downsample factor.
                // An 8000x4000 photo would be ~128MB as ARGB_8888 and exceeds
                // Canvas' max bitmap size (~100MB), crashing with
                // "Canvas: trying to draw too large(...) bitmap."
                // We cap the loaded bitmap to at most 2048 on its longest side;
                // the final cropped result is scaled to 1024x1024 anyway, so this
                // preserves enough detail for cropping while staying well under
                // the Canvas limit (~16MB for 2048x2048).
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                try {
                    context.contentResolver.openInputStream(parsed)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, boundsOpts)
                    }
                } catch (_: Exception) { /* ignore, fallback to full decode */ }

                val outW = boundsOpts.outWidth
                val outH = boundsOpts.outHeight
                val maxSide = 2048
                var sampleSize = 1
                if (outW > 0 && outH > 0) {
                    var maxDim = maxOf(outW, outH)
                    // BitmapFactory requires power-of-2 sample size for best efficiency
                    while (maxDim / sampleSize > maxSide) {
                        sampleSize *= 2
                    }
                } else {
                    // If bounds failed, use a conservative sample to avoid OOM on huge images
                    sampleSize = 2
                }

                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    // Prefer ARGB_8888 for cropping quality; downsampling already limits memory
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                var result: Bitmap? = null
                try {
                    context.contentResolver.openInputStream(parsed)?.use { stream ->
                        result = BitmapFactory.decodeStream(stream, null, decodeOpts)
                    }
                } catch (_: Exception) { /* fall through */ }

                // If first decode failed (bounds gave 0 etc.), try without sampling as fallback
                if (result == null && sampleSize != 1) {
                    try {
                        context.contentResolver.openInputStream(parsed)?.use { stream ->
                            result = BitmapFactory.decodeStream(stream) ?: result
                        }
                    } catch (_: Exception) { /* ignore */ }
                }
                result
            } catch (_: Exception) { null }
        }
    }

    DisposableEffect(Unit) {
        onDispose { bitmap?.recycle() }
    }

    AppScaffold(
        title = stringResource(R.string.crop_photo),
        navigationIcon = { CropPhotoSection(onCancel) },
        actions = {
            TextButton(onClick = {
                val bmp = bitmap ?: return@TextButton
                if (imgDisplayW <= 0f || imgDisplayH <= 0f) return@TextButton

                val scaleX = bmp.width.toFloat() / imgDisplayW
                val scaleY = bmp.height.toFloat() / imgDisplayH
                val cx = ((cropOffset.x - imgDisplayOffset.x) * scaleX).roundToInt()
                    .coerceIn(0, bmp.width - 1)
                val cy = ((cropOffset.y - imgDisplayOffset.y) * scaleY).roundToInt()
                    .coerceIn(0, bmp.height - 1)
                val cs = (cropSize * scaleX).roundToInt()
                    .coerceIn(1, min(bmp.width - cx, bmp.height - cy))

                val cropped = Bitmap.createBitmap(bmp, cx, cy, cs, cs)
                val scaled = cropped.scale(1024, 1024)
                if (cropped !== scaled) cropped.recycle()
                onCropComplete(scaled)
            }) { Text(stringResource(UiR.string.done)) }
        },
    ) { padding ->
        val currentBitmap = bitmap ?: return@AppScaffold

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val maxW = constraints.maxWidth.toFloat()
            val maxH = constraints.maxHeight.toFloat()

            val scale = (maxW / currentBitmap.width).coerceAtMost(maxH / currentBitmap.height)
            val imgW = currentBitmap.width * scale
            val imgH = currentBitmap.height * scale
            val imgOffsetX = (maxW - imgW) / 2f
            val imgOffsetY = (maxH - imgH) / 2f

            imgDisplayW = imgW
            imgDisplayH = imgH
            imgDisplayOffset = Offset(imgOffsetX, imgOffsetY)

            if (!initialized) {
                val initSize = min(imgW, imgH) * 0.8f
                cropSize = initSize
                cropOffset = Offset((maxW - initSize) / 2f, (maxH - initSize) / 2f)
                initialized = true
            }

            var activeDragHandle by remember { mutableStateOf(DragHandle.None) }
            val handleTouchRadius = with(LocalDensity.current) { 24.dp.toPx() }
            val minCropSize = with(LocalDensity.current) { 80.dp.toPx() }

            val imageBitmap = remember(currentBitmap) { currentBitmap.asImageBitmap() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val handlePositions = mapOf(
                                    DragHandle.TopLeft to cropOffset,
                                    DragHandle.Top to Offset(cropOffset.x + cropSize / 2f, cropOffset.y),
                                    DragHandle.TopRight to Offset(cropOffset.x + cropSize, cropOffset.y),
                                    DragHandle.Right to Offset(cropOffset.x + cropSize, cropOffset.y + cropSize / 2f),
                                    DragHandle.BottomRight to Offset(cropOffset.x + cropSize, cropOffset.y + cropSize),
                                    DragHandle.Bottom to Offset(cropOffset.x + cropSize / 2f, cropOffset.y + cropSize),
                                    DragHandle.BottomLeft to Offset(cropOffset.x, cropOffset.y + cropSize),
                                    DragHandle.Left to Offset(cropOffset.x, cropOffset.y + cropSize / 2f)
                                )

                                val nearest = handlePositions.minByOrNull { (pos - it.value).getDistance() }
                                activeDragHandle = if (nearest != null && (pos - nearest.value).getDistance() <= handleTouchRadius) {
                                    nearest.key
                                } else {
                                    val cropRect = Rect(cropOffset.x, cropOffset.y, cropOffset.x + cropSize, cropOffset.y + cropSize)
                                    if (cropRect.contains(pos)) DragHandle.Move else DragHandle.None
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val pos = change.position
                                when (activeDragHandle) {
                                    DragHandle.Move -> {
                                        val delta = change.position - change.previousPosition
                                        val newX = (cropOffset.x + delta.x).coerceIn(imgOffsetX, imgOffsetX + imgW - cropSize)
                                        val newY = (cropOffset.y + delta.y).coerceIn(imgOffsetY, imgOffsetY + imgH - cropSize)
                                        cropOffset = Offset(newX, newY)
                                    }
                                    DragHandle.BottomRight -> {
                                        val anchorX = cropOffset.x
                                        val anchorY = cropOffset.y
                                        val newSize = maxOf(pos.x - anchorX, pos.y - anchorY)
                                            .coerceIn(minCropSize, minOf(imgOffsetX + imgW - anchorX, imgOffsetY + imgH - anchorY))
                                        cropSize = newSize
                                    }
                                    DragHandle.TopLeft -> {
                                        val anchorX = cropOffset.x + cropSize
                                        val anchorY = cropOffset.y + cropSize
                                        val newSize = maxOf(anchorX - pos.x, anchorY - pos.y)
                                            .coerceIn(minCropSize, minOf(anchorX - imgOffsetX, anchorY - imgOffsetY))
                                        cropOffset = Offset(anchorX - newSize, anchorY - newSize)
                                        cropSize = newSize
                                    }
                                    DragHandle.TopRight -> {
                                        val anchorX = cropOffset.x
                                        val anchorY = cropOffset.y + cropSize
                                        val newSize = maxOf(pos.x - anchorX, anchorY - pos.y)
                                            .coerceIn(minCropSize, minOf(imgOffsetX + imgW - anchorX, anchorY - imgOffsetY))
                                        cropOffset = Offset(anchorX, anchorY - newSize)
                                        cropSize = newSize
                                    }
                                    DragHandle.BottomLeft -> {
                                        val anchorX = cropOffset.x + cropSize
                                        val anchorY = cropOffset.y
                                        val newSize = maxOf(anchorX - pos.x, pos.y - anchorY)
                                            .coerceIn(minCropSize, minOf(anchorX - imgOffsetX, imgOffsetY + imgH - anchorY))
                                        cropOffset = Offset(anchorX - newSize, anchorY)
                                        cropSize = newSize
                                    }
                                    DragHandle.Right -> {
                                        val left = cropOffset.x
                                        val centerY = cropOffset.y + cropSize / 2f
                                        val newSize = (pos.x - left)
                                            .coerceIn(minCropSize, imgOffsetX + imgW - left)
                                        val newTop = (centerY - newSize / 2f).coerceAtLeast(imgOffsetY)
                                        val adjustedSize = minOf(newSize, imgOffsetY + imgH - newTop)
                                        cropOffset = Offset(left, newTop)
                                        cropSize = adjustedSize
                                    }
                                    DragHandle.Left -> {
                                        val right = cropOffset.x + cropSize
                                        val centerY = cropOffset.y + cropSize / 2f
                                        val newSize = (right - pos.x)
                                            .coerceIn(minCropSize, right - imgOffsetX)
                                        val newTop = (centerY - newSize / 2f).coerceAtLeast(imgOffsetY)
                                        val adjustedSize = minOf(newSize, imgOffsetY + imgH - newTop)
                                        cropOffset = Offset(right - adjustedSize, newTop)
                                        cropSize = adjustedSize
                                    }
                                    DragHandle.Bottom -> {
                                        val top = cropOffset.y
                                        val centerX = cropOffset.x + cropSize / 2f
                                        val newSize = (pos.y - top)
                                            .coerceIn(minCropSize, imgOffsetY + imgH - top)
                                        val newLeft = (centerX - newSize / 2f).coerceAtLeast(imgOffsetX)
                                        val adjustedSize = minOf(newSize, imgOffsetX + imgW - newLeft)
                                        cropOffset = Offset(newLeft, top)
                                        cropSize = adjustedSize
                                    }
                                    DragHandle.Top -> {
                                        val bottom = cropOffset.y + cropSize
                                        val centerX = cropOffset.x + cropSize / 2f
                                        val newSize = (bottom - pos.y)
                                            .coerceIn(minCropSize, bottom - imgOffsetY)
                                        val newLeft = (centerX - newSize / 2f).coerceAtLeast(imgOffsetX)
                                        val adjustedSize = minOf(newSize, imgOffsetX + imgW - newLeft)
                                        cropOffset = Offset(newLeft, bottom - adjustedSize)
                                        cropSize = adjustedSize
                                    }
                                    DragHandle.None -> {}
                                }
                            },
                            onDragEnd = { activeDragHandle = DragHandle.None }
                        )
                    }
            ) {
                drawImage(
                    image = imageBitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(currentBitmap.width, currentBitmap.height),
                    dstOffset = IntOffset(imgOffsetX.roundToInt(), imgOffsetY.roundToInt()),
                    dstSize = IntSize(imgW.roundToInt(), imgH.roundToInt())
                )

                val cropCenter = Offset(cropOffset.x + cropSize / 2f, cropOffset.y + cropSize / 2f)
                val radius = cropSize / 2f

                val overlayPath = Path().apply {
                    addRect(Rect(0f, 0f, maxW, maxH))
                    addOval(Rect(cropCenter.x - radius, cropCenter.y - radius, cropCenter.x + radius, cropCenter.y + radius))
                    fillType = PathFillType.EvenOdd
                }
                drawPath(overlayPath, Color.Black.copy(alpha = 0.6f))

                drawRect(
                    color = Color.White,
                    topLeft = cropOffset,
                    size = Size(cropSize, cropSize),
                    style = Stroke(width = 2.dp.toPx())
                )

                val handleRadius = 6.dp.toPx()
                val handles = listOf(
                    cropOffset,
                    Offset(cropOffset.x + cropSize / 2f, cropOffset.y),
                    Offset(cropOffset.x + cropSize, cropOffset.y),
                    Offset(cropOffset.x + cropSize, cropOffset.y + cropSize / 2f),
                    Offset(cropOffset.x + cropSize, cropOffset.y + cropSize),
                    Offset(cropOffset.x + cropSize / 2f, cropOffset.y + cropSize),
                    Offset(cropOffset.x, cropOffset.y + cropSize),
                    Offset(cropOffset.x, cropOffset.y + cropSize / 2f)
                )
                handles.forEach { pos ->
                    drawCircle(Color.White, radius = handleRadius, center = pos)
                }
            }
        }
    }
}
