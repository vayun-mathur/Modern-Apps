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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.model.Quadrilateral
import kotlinx.coroutines.Dispatchers
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

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
                originalSize = IntSize(bitmap.width, bitmap.height)
                decodedBitmap = bitmap
            } catch (e: Exception) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                originalSize = IntSize(options.outWidth, options.outHeight)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { decodedBitmap?.recycle() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crop_image)) },
                navigationIcon = {
                    IconNavigation(navBack = onBack)
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onCropDone(quadrilateral) },
                text = { Text(stringResource(R.string.continue_label)) },
                icon = { IconSave() }
            )
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
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

        val corners = listOf(
            quadrilateral.topLeft,
            quadrilateral.topRight,
            quadrilateral.bottomRight,
            quadrilateral.bottomLeft
        )
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

        // Corner index: 0=TL, 1=TR, 2=BR, 3=BL
        CropHandle(offset = screenCorners[0], onDragStart = { activeDragCorner = 0 }, onDragEnd = { activeDragCorner = null }) { delta ->
            val newX = (quadrilateral.topLeft.x + delta.x / width).coerceIn(0f, 1f)
            val newY = (quadrilateral.topLeft.y + delta.y / height).coerceIn(0f, 1f)
            onQuadrilateralChange(quadrilateral.copy(topLeft = Offset(newX, newY)))
        }
        CropHandle(offset = screenCorners[1], onDragStart = { activeDragCorner = 1 }, onDragEnd = { activeDragCorner = null }) { delta ->
            val newX = (quadrilateral.topRight.x + delta.x / width).coerceIn(0f, 1f)
            val newY = (quadrilateral.topRight.y + delta.y / height).coerceIn(0f, 1f)
            onQuadrilateralChange(quadrilateral.copy(topRight = Offset(newX, newY)))
        }
        CropHandle(offset = screenCorners[2], onDragStart = { activeDragCorner = 2 }, onDragEnd = { activeDragCorner = null }) { delta ->
            val newX = (quadrilateral.bottomRight.x + delta.x / width).coerceIn(0f, 1f)
            val newY = (quadrilateral.bottomRight.y + delta.y / height).coerceIn(0f, 1f)
            onQuadrilateralChange(quadrilateral.copy(bottomRight = Offset(newX, newY)))
        }
        CropHandle(offset = screenCorners[3], onDragStart = { activeDragCorner = 3 }, onDragEnd = { activeDragCorner = null }) { delta ->
            val newX = (quadrilateral.bottomLeft.x + delta.x / width).coerceIn(0f, 1f)
            val newY = (quadrilateral.bottomLeft.y + delta.y / height).coerceIn(0f, 1f)
            onQuadrilateralChange(quadrilateral.copy(bottomLeft = Offset(newX, newY)))
        }

        activeDragCorner?.let { cornerIdx ->
            MagnifierWindow(
                bitmap = bitmap,
                normalizedCorners = corners,
                activeCorner = cornerIdx,
                handleScreenPos = screenCorners[cornerIdx],
                overlayWidth = width,
                overlayHeight = height
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
    overlayWidth: Float,
    overlayHeight: Float
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

@Composable
fun CropHandle(offset: Offset, onDragStart: () -> Unit = {}, onDragEnd: () -> Unit = {}, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val handleSize = 24.dp
    val handleRadiusPx = with(density) { (handleSize / 2).toPx() }
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Box(modifier = Modifier
        .offset { IntOffset((offset.x - handleRadiusPx).roundToInt(), (offset.y - handleRadiusPx).roundToInt()) }
        .size(handleSize)
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { currentOnDragStart() },
                onDragEnd = { currentOnDragEnd() },
                onDragCancel = { currentOnDragEnd() },
                onDrag = { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount)
                }
            )
        }
        .background(Color.White, androidx.compose.foundation.shape.CircleShape)
    )
}
