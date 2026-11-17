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
