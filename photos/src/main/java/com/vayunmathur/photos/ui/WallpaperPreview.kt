package com.vayunmathur.photos.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Text
import com.vayunmathur.photos.R
import com.vayunmathur.photos.platform.WallpaperLoadState
import com.vayunmathur.photos.platform.WallpaperUtil

internal data class PreviewSnapshot(
    val containerW: Float,
    val containerH: Float,
    val baseW: Float,
    val baseH: Float,
    val viewportW: Float,
    val viewportH: Float,
)

/**
 * Lavender-style preview: the image with pinch/pan zoom, a dim scrim outside the
 * phone-shaped viewport, and a rounded viewport border. Reports its layout snapshot
 * so the Set button always crops what is currently on screen.
 */
@Composable
internal fun WallpaperPreview(
    bitmap: Bitmap?,
    loadState: WallpaperLoadState,
    targetAspect: Float,
    zoomScale: Float,
    zoomOffset: Offset,
    /** Fresh reads for the gesture lambda without capturing stale composition values. */
    currentScale: State<Float>,
    currentOffset: State<Offset>,
    onZoomChange: (scale: Float, offset: Offset) -> Unit,
    onCoverMin: (Float) -> Unit,
    onSnapshot: (PreviewSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ----- preview: Lavender-style dim outside viewport ----------------
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        when {
            loadState == WallpaperLoadState.Loading ||
                (bitmap == null && loadState == WallpaperLoadState.Idle) -> {
                // Viewer context without a scaffold: text affordance, no loading icon.
                Text("Loading…", color = Color.White)
            }
            loadState == WallpaperLoadState.Failed || bitmap == null -> {
                Text(
                    stringResource(R.string.wallpaper_load_failed),
                    color = Color.White,
                )
            }
            else -> {
                val bmp = bitmap!!

                val fitScale = minOf(
                    containerW / bmp.width.toFloat(),
                    containerH / bmp.height.toFloat(),
                ).coerceAtLeast(0.01f)
                val baseDisplayW = bmp.width * fitScale
                val baseDisplayH = bmp.height * fitScale

                val (viewportW, viewportH) = run {
                    val maxW = containerW * 0.90f
                    val maxH = containerH * 0.90f
                    var vw = maxH * targetAspect
                    var vh = maxH
                    if (vw > maxW) {
                        vw = maxW
                        vh = maxW / targetAspect
                    }
                    vw to vh
                }

                val coverMin = WallpaperUtil.coverMinScale(
                    baseDisplayW = baseDisplayW,
                    baseDisplayH = baseDisplayH,
                    viewportW = viewportW,
                    viewportH = viewportH,
                )

                // Push snapshot synchronously after composition committed (SideEffect) so rotation is not stale.
                SideEffect {
                    onSnapshot(
                        PreviewSnapshot(
                            containerW = containerW,
                            containerH = containerH,
                            baseW = baseDisplayW,
                            baseH = baseDisplayH,
                            viewportW = viewportW,
                            viewportH = viewportH,
                        )
                    )
                }

                LaunchedEffect(coverMin) {
                    onCoverMin(coverMin)
                }

                fun clampOffset(off: Offset, scale: Float): Offset {
                    val effW = baseDisplayW * scale
                    val effH = baseDisplayH * scale
                    val maxX = kotlin.math.max(0f, (effW - viewportW) / 2f)
                    val maxY = kotlin.math.max(0f, (effH - viewportH) / 2f)
                    return Offset(
                        x = off.x.coerceIn(-maxX, maxX),
                        y = off.y.coerceIn(-maxY, maxY),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(baseDisplayW, baseDisplayH, viewportW, viewportH, coverMin) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()

                                    if (zoomChange != 1f || panChange != Offset.Zero) {
                                        val newScale = (currentScale.value * zoomChange)
                                            .coerceIn(coverMin, 5f)
                                        val newOffset = clampOffset(
                                            currentOffset.value + panChange,
                                            newScale,
                                        )
                                        onZoomChange(newScale, newOffset)
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .size(
                                with(density) { baseDisplayW.toDp() },
                                with(density) { baseDisplayH.toDp() },
                            )
                            .graphicsLayer {
                                scaleX = zoomScale
                                scaleY = zoomScale
                                translationX = zoomOffset.x
                                translationY = zoomOffset.y
                            },
                        contentScale = ContentScale.FillBounds,
                    )

                    // Lavender-style: dim scrim outside viewport (4-rect hole)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cw = size.width
                        val ch = size.height
                        val vpLeft = (cw - viewportW) / 2f
                        val vpTop = (ch - viewportH) / 2f
                        val dim = Color.Black.copy(alpha = 0.60f)

                        // Top
                        drawRect(dim, topLeft = Offset(0f, 0f), size = ComposeSize(cw, vpTop))
                        // Bottom
                        drawRect(
                            dim,
                            topLeft = Offset(0f, vpTop + viewportH),
                            size = ComposeSize(cw, ch - (vpTop + viewportH)),
                        )
                        // Left
                        drawRect(dim, topLeft = Offset(0f, vpTop), size = ComposeSize(vpLeft, viewportH))
                        // Right
                        drawRect(
                            dim,
                            topLeft = Offset(vpLeft + viewportW, vpTop),
                            size = ComposeSize(cw - (vpLeft + viewportW), viewportH),
                        )
                    }

                    // Phone-shaped viewport border — rounded 28dp, white 60% (Lavender polish)
                    Box(
                        modifier = Modifier
                            .size(
                                with(density) { viewportW.toDp() },
                                with(density) { viewportH.toDp() },
                            )
                            .border(
                                width = 1.5.dp,
                                color = Color.White.copy(alpha = 0.75f),
                                shape = RoundedCornerShape(28.dp),
                            ),
                    )
                }
            }
        }
    }
}
