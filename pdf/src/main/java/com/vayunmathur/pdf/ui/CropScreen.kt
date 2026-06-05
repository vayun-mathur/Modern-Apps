package com.vayunmathur.pdf.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
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

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                // Use ImageDecoder which respects EXIF orientation
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
                originalSize = IntSize(bitmap.width, bitmap.height)
                bitmap.recycle()
            } catch (e: Exception) {
                // Fallback to BitmapFactory if ImageDecoder fails
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                originalSize = IntSize(options.outWidth, options.outHeight)
            }
        }
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
                            onQuadrilateralChange = { quadrilateral = it }
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
fun CropOverlay(quadrilateral: Quadrilateral, onQuadrilateralChange: (Quadrilateral) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        // Convert normalized coordinates to screen coordinates
        val topLeft = Offset(quadrilateral.topLeft.x * width, quadrilateral.topLeft.y * height)
        val topRight = Offset(quadrilateral.topRight.x * width, quadrilateral.topRight.y * height)
        val bottomRight = Offset(quadrilateral.bottomRight.x * width, quadrilateral.bottomRight.y * height)
        val bottomLeft = Offset(quadrilateral.bottomLeft.x * width, quadrilateral.bottomLeft.y * height)

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw dark overlay with quadrilateral cutout
            val path = Path().apply {
                // Outer rectangle (full screen)
                addRect(Rect(0f, 0f, width, height))
                // Inner quadrilateral (the crop area) - using EvenOdd to create a hole
                moveTo(topLeft.x, topLeft.y)
                lineTo(topRight.x, topRight.y)
                lineTo(bottomRight.x, bottomRight.y)
                lineTo(bottomLeft.x, bottomLeft.y)
                close()
                fillType = PathFillType.EvenOdd
            }
            drawPath(path, Color.Black.copy(alpha = 0.5f))

            // Draw quadrilateral border
            val borderPath = Path().apply {
                moveTo(topLeft.x, topLeft.y)
                lineTo(topRight.x, topRight.y)
                lineTo(bottomRight.x, bottomRight.y)
                lineTo(bottomLeft.x, bottomLeft.y)
                close()
            }
            drawPath(borderPath, Color.White, style = Stroke(width = 2.dp.toPx()))
        }

        // Corner handles - each can be dragged independently
        CropHandle(offset = topLeft) { delta ->
            val newX = (quadrilateral.topLeft.x + delta.x / width).coerceIn(0f, 1f)
            val newY = (quadrilateral.topLeft.y + delta.y / height).coerceIn(0f, 1f)
            onQuadrilateralChange(quadrilateral.copy(topLeft = Offset(newX, newY)))
        }
        CropHandle(offset = topRight) { delta ->
            val newX = (quadrilateral.topRight.x + delta.x / width).coerceIn(0f, 1f)
            val newY = (quadrilateral.topRight.y + delta.y / height).coerceIn(0f, 1f)
            onQuadrilateralChange(quadrilateral.copy(topRight = Offset(newX, newY)))
        }
        CropHandle(offset = bottomRight) { delta ->
            val newX = (quadrilateral.bottomRight.x + delta.x / width).coerceIn(0f, 1f)
            val newY = (quadrilateral.bottomRight.y + delta.y / height).coerceIn(0f, 1f)
            onQuadrilateralChange(quadrilateral.copy(bottomRight = Offset(newX, newY)))
        }
        CropHandle(offset = bottomLeft) { delta ->
            val newX = (quadrilateral.bottomLeft.x + delta.x / width).coerceIn(0f, 1f)
            val newY = (quadrilateral.bottomLeft.y + delta.y / height).coerceIn(0f, 1f)
            onQuadrilateralChange(quadrilateral.copy(bottomLeft = Offset(newX, newY)))
        }
    }
}

@Composable
fun CropHandle(offset: Offset, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val handleSize = 24.dp
    val handleRadiusPx = with(density) { (handleSize / 2).toPx() }
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(modifier = Modifier
        .offset { IntOffset((offset.x - handleRadiusPx).roundToInt(), (offset.y - handleRadiusPx).roundToInt()) }
        .size(handleSize)
        .pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                currentOnDrag(dragAmount)
            }
        }
        .background(Color.White, androidx.compose.foundation.shape.CircleShape)
    )
}
