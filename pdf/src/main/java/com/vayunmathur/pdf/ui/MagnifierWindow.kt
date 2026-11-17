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
import com.vayunmathur.pdf.data.Quadrilateral
import com.vayunmathur.pdf.platform.AutoFrameDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
    // overlayWidth can be narrower than the 120dp magnifier for very tall
    // captures fitted to height; floor the upper bound so coerceIn never sees
    // a min greater than its max (which throws IllegalArgumentException).
    val magnifierX = (handleScreenPos.x - magnifierSizePx / 2f)
        .coerceIn(0f, (overlayWidth - magnifierSizePx).coerceAtLeast(0f))

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
