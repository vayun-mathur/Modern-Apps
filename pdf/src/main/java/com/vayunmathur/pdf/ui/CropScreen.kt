package com.vayunmathur.pdf.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExtendedFloatingActionButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.model.Quadrilateral
import com.vayunmathur.pdf.util.AutoFrameDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    uri: Uri,
    initialQuadrilateral: Quadrilateral?,
    onCropDone: (Quadrilateral) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var quadrilateral by remember {
        mutableStateOf(initialQuadrilateral ?: Quadrilateral.default())
    }
    var originalSize by remember { mutableStateOf<IntSize?>(null) }
    var decodedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uri) {
        val bitmap = withContext(Dispatchers.IO) {
            try {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } catch (_: Exception) { null }
        }
        if (bitmap != null) {
            originalSize = IntSize(bitmap.width, bitmap.height)
            decodedBitmap = bitmap
            if (initialQuadrilateral == null) {
                withContext(Dispatchers.Default) {
                    AutoFrameDetector.detect(bitmap)
                }?.let { quadrilateral = it }
            }
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                    // Zero dimensions mean the bounds decode failed (null stream or
                    // undecodable data); leave originalSize null so the screen keeps
                    // showing its loading state instead of dividing by zero below.
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        originalSize = IntSize(options.outWidth, options.outHeight)
                    }
                }
            }
        }
    }

    AppScaffold(
        title = stringResource(R.string.crop_image),
        onNavigateBack = onBack,
        actions = {
            Button(
                onClick = {
                    decodedBitmap?.let { bmp ->
                        scope.launch {
                            withContext(Dispatchers.Default) {
                                AutoFrameDetector.detect(bmp)
                            }?.let { quadrilateral = it }
                        }
                    }
                },
                enabled = decodedBitmap != null
            ) {
                Text(stringResource(R.string.auto_detect))
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = 32.dp, bottom = 64.dp, start = 48.dp, end = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            val maxWidth = constraints.maxWidth.toFloat()
            val maxHeight = constraints.maxHeight.toFloat()

            originalSize?.let { size ->
                val photoRatio = size.width.toFloat() / size.height.toFloat()
                val containerRatio = maxWidth / maxHeight
                // Calculate size to fit within container while maintaining aspect ratio
                // Use maximum available space
                val (viewportWidth, viewportHeight) = if (photoRatio > containerRatio) {
                    // Photo is wider than container - fit to width
                    maxWidth to (maxWidth / photoRatio)
                } else {
                    // Photo is taller than container - fit to height
                    (maxHeight * photoRatio) to maxHeight
                }

                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Single box with exact size containing both image and overlay
                    // This ensures they are perfectly aligned
                    Box(
                        Modifier.size(
                            with(LocalDensity.current) { viewportWidth.toDp() },
                            with(LocalDensity.current) { viewportHeight.toDp() }
                        )
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        CropOverlay(
                            quadrilateral = quadrilateral,
                            onQuadrilateralChange = { quadrilateral = it },
                            bitmap = decodedBitmap
                        )
                    }
                }
            } ?: AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
        ExtendedFloatingActionButton(
            onClick = { onCropDone(quadrilateral) },
            text = { Text(stringResource(R.string.continue_label)) },
            icon = { IconSave() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
        }
    }
}

@Composable
fun CropOverlay(
    quadrilateral: Quadrilateral,
    onQuadrilateralChange: (Quadrilateral) -> Unit,
    bitmap: Bitmap?
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        var activeDragCorner by remember { mutableStateOf<Int?>(null) }

        val corners = quadrilateral.corners()
        val screenCorners = corners.map { Offset(it.x * width, it.y * height) }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val path = Path().apply {
                addRect(Rect(0f, 0f, width, height))
                moveTo(screenCorners[0].x, screenCorners[0].y)
                lineTo(screenCorners[1].x, screenCorners[1].y)
                lineTo(screenCorners[2].x, screenCorners[2].y)
                lineTo(screenCorners[3].x, screenCorners[3].y)
                close()
                fillType = PathFillType.EvenOdd
            }
            drawPath(path, Color.Black.copy(alpha = 0.5f))

            val borderPath = Path().apply {
                moveTo(screenCorners[0].x, screenCorners[0].y)
                lineTo(screenCorners[1].x, screenCorners[1].y)
                lineTo(screenCorners[2].x, screenCorners[2].y)
                lineTo(screenCorners[3].x, screenCorners[3].y)
                close()
            }
            drawPath(borderPath, Color.White, style = Stroke(width = 2.dp.toPx()))
        }

        for (i in 0..3) {
            CropScreenSection(offset = screenCorners[i], onDragStart = { activeDragCorner = i }, onDragEnd = { activeDragCorner = null }) { delta ->
                val c = quadrilateral.corner(i)
                val newPos = Offset(
                    (c.x + delta.x / width).coerceIn(0f, 1f),
                    (c.y + delta.y / height).coerceIn(0f, 1f)
                )
                onQuadrilateralChange(quadrilateral.withCorner(i, newPos))
            }
        }

        activeDragCorner?.let { cornerIdx ->
            MagnifierWindow(
                bitmap = bitmap,
                normalizedCorners = corners,
                activeCorner = cornerIdx,
                handleScreenPos = screenCorners[cornerIdx],
                overlayWidth = width
            )
        }
    }
}

@Composable
fun MagnifierWindow(
    bitmap: Bitmap?,
    normalizedCorners: List<Offset>,
    activeCorner: Int,
    handleScreenPos: Offset,
    overlayWidth: Float
) {
    if (bitmap == null) return

    val density = LocalDensity.current
    val magnifierSizeDp = 120.dp
    val magnifierSizePx = with(density) { magnifierSizeDp.toPx() }
    val gapPx = with(density) { 80.dp.toPx() }
    val zoom = 3f

    val magnifierY = handleScreenPos.y - magnifierSizePx - gapPx
    val magnifierX = (handleScreenPos.x - magnifierSizePx / 2f)
        .coerceIn(0f, overlayWidth - magnifierSizePx)

    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .offset { IntOffset(magnifierX.roundToInt(), magnifierY.roundToInt()) }
            .size(magnifierSizeDp)
            .clip(shape)
            .background(Color.Black)
            .border(2.dp, Color.White, shape)
    ) {
        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
        val cornerNorm = normalizedCorners[activeCorner]
        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()

        Canvas(modifier = Modifier.fillMaxSize()) {
            val srcCenterX = cornerNorm.x * bmpW
            val srcCenterY = cornerNorm.y * bmpH
            val srcRegionSize = magnifierSizePx / zoom

            val srcLeft = (srcCenterX - srcRegionSize / 2f).coerceIn(0f, (bmpW - srcRegionSize).coerceAtLeast(0f))
            val srcTop = (srcCenterY - srcRegionSize / 2f).coerceIn(0f, (bmpH - srcRegionSize).coerceAtLeast(0f))

            drawImage(
                image = imageBitmap,
                srcOffset = androidx.compose.ui.unit.IntOffset(srcLeft.roundToInt(), srcTop.roundToInt()),
                srcSize = IntSize(srcRegionSize.roundToInt().coerceAtMost(bitmap.width), srcRegionSize.roundToInt().coerceAtMost(bitmap.height)),
                dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                dstSize = IntSize(magnifierSizePx.roundToInt(), magnifierSizePx.roundToInt())
            )

            // Crosshair at center
            val centerX = magnifierSizePx / 2f
            val centerY = magnifierSizePx / 2f
            val crosshairRadius = 8.dp.toPx()
            drawCircle(Color.White, radius = crosshairRadius, center = Offset(centerX, centerY), style = Stroke(width = 1.5.dp.toPx()))
            drawLine(Color.White, Offset(centerX - crosshairRadius, centerY), Offset(centerX + crosshairRadius, centerY), strokeWidth = 1.dp.toPx())
            drawLine(Color.White, Offset(centerX, centerY - crosshairRadius), Offset(centerX, centerY + crosshairRadius), strokeWidth = 1.dp.toPx())

            // Draw the two edges emanating from this corner
            val prevIdx = (activeCorner + 3) % 4
            val nextIdx = (activeCorner + 1) % 4

            fun toMagnifierSpace(normPt: Offset): Offset {
                val bmpX = normPt.x * bmpW
                val bmpY = normPt.y * bmpH
                return Offset(
                    (bmpX - srcLeft) * zoom,
                    (bmpY - srcTop) * zoom
                )
            }

            val magCorner = toMagnifierSpace(normalizedCorners[activeCorner])
            val magPrev = toMagnifierSpace(normalizedCorners[prevIdx])
            val magNext = toMagnifierSpace(normalizedCorners[nextIdx])

            drawLine(Color.Yellow, magCorner, magPrev, strokeWidth = 1.5.dp.toPx())
            drawLine(Color.Yellow, magCorner, magNext, strokeWidth = 1.5.dp.toPx())
        }
    }
}
