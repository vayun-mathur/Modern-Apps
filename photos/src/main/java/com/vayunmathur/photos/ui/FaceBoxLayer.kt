package com.vayunmathur.photos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.photos.util.PhotoFaceBoxes
import kotlin.math.roundToInt

/**
 * Outlines the faces detected in a photo, with the person's name where there is
 * one, and opens that person's photo set when a face is tapped.
 *
 * [boxes] are normalised against the detection bitmap, so they are mapped through
 * the same `ContentScale.Fit` letterbox the image is drawn in — see
 * [imageFitTransform], which the OCR overlay also uses — against the source
 * dimensions stored with the faces rather than MediaStore's un-EXIF-corrected
 * ones.
 *
 * The caller passes the image's zoom/pan `graphicsLayer` in [modifier], so the
 * GPU transform carries the boxes and there is no pan/zoom maths here. Only the
 * things that must hold their on-screen size are counter-scaled by [zoom]: the
 * stroke widths, and the name labels.
 *
 * [zoom] is a lambda, not a value, so it is read inside the draw and layer
 * lambdas below rather than during composition — a pinch re-runs those without
 * recomposing this layer or anything around it.
 *
 * Colours are fixed rather than themed for the reason spelled out on
 * [OcrTextLayer]: a dynamic-colour pastel vanishes over a bright photo, so every
 * line is a light stroke sitting inside a dark halo.
 */
@Composable
fun FaceBoxLayer(
    boxes: PhotoFaceBoxes,
    containerSize: IntSize,
    zoom: () -> Float,
    onFaceClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val placements = remember(boxes, containerSize) {
        val fit = imageFitTransform(boxes.srcWidth, boxes.srcHeight, containerSize)
            ?: return@remember emptyList<FacePlacement>()
        boxes.faces.mapNotNull { face ->
            val left = fit.originX + face.left * boxes.srcWidth * fit.scale
            val top = fit.originY + face.top * boxes.srcHeight * fit.scale
            val right = fit.originX + face.right * boxes.srcWidth * fit.scale
            val bottom = fit.originY + face.bottom * boxes.srcHeight * fit.scale
            if (right <= left || bottom <= top) return@mapNotNull null
            FacePlacement(
                clusterId = face.clusterId,
                name = face.name,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
            )
        }
    }
    if (placements.isEmpty()) return

    val density = LocalDensity.current

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Pre-divided by the zoom this whole layer sits inside, so the lines
            // stay a constant thickness on screen instead of ballooning with the
            // photo.
            val scale = zoom()
            val halo = OUTLINE_HALO.toPx() / scale
            val line = OUTLINE_STROKE.toPx() / scale
            val corner = CornerRadius(BOX_CORNER.toPx() / scale)
            placements.forEach { p ->
                // Wide dark stroke first, narrow light stroke centred inside it:
                // one of the two always contrasts, whatever is underneath.
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.55f),
                    topLeft = p.topLeft,
                    size = p.size,
                    cornerRadius = corner,
                    style = Stroke(width = halo),
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.9f),
                    topLeft = p.topLeft,
                    size = p.size,
                    cornerRadius = corner,
                    style = Stroke(width = line),
                )
            }
        }

        placements.forEach { p ->
            // One transparent tap target per face. Compose hit-tests through the
            // zoom graphicsLayer, so positioning these in un-zoomed container
            // space is correct while the photo is magnified.
            // Faces that belong to no person are outlined but not tappable.
            p.clusterId?.let { clusterId ->
                Box(
                    Modifier
                        .offset { IntOffset(p.topLeft.x.roundToInt(), p.topLeft.y.roundToInt()) }
                        .requiredSize(
                            width = with(density) { p.size.width.toDp() },
                            height = with(density) { p.size.height.toDp() },
                        )
                        .invisibleClickable { onFaceClick(clusterId) }
                )
            }

            // Unnamed clusters are left as bare outlines rather than labelled
            // with a placeholder.
            if (p.name != null) {
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                p.topLeft.x.roundToInt(),
                                (p.topLeft.y + p.size.height).roundToInt(),
                            )
                        }
                        // Counter-scaled from its own anchor for the same reason
                        // the strokes are divided above: without this the text
                        // grows with pinch-zoom.
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            val inverse = 1f / zoom()
                            scaleX = inverse
                            scaleY = inverse
                        }
                ) {
                    Text(
                        text = p.name,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .background(
                                color = Color.Black.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** One face resolved to on-screen pixels of the un-zoomed container. */
private data class FacePlacement(
    val clusterId: Long?,
    val name: String?,
    val topLeft: Offset,
    val size: Size,
)

private val OUTLINE_STROKE = 1.dp
private val OUTLINE_HALO = 3.dp
private val BOX_CORNER = 6.dp
