package com.vayunmathur.photos.ui

import com.vayunmathur.library.ui.FadeVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.photos.data.OcrLayout
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * An invisible, selectable copy of a photo's recognised text laid over the image,
 * so text in a screenshot or a sign can be long-pressed, dragged over and copied
 * with the platform's own selection UI (Copy / Select all / Share / Translate).
 *
 * [layout]'s coordinates are pixels of the bitmap OCR ran on, so each box is
 * mapped through the `ContentScale.Fit` letterbox this image is drawn in — hence
 * [containerSize] and the layout's own dimensions rather than [com.vayunmathur.photos.data.Photo.width].
 * The caller passes the image's zoom/pan `graphicsLayer` in [modifier] so the
 * text tracks pinch-zoom in lockstep. [zoom] is a lambda so the stroke
 * counter-scaling reads it in the draw phase, and a pinch redraws without
 * recomposing.
 *
 * Everything drawn here has to stay legible on top of an arbitrary photo, which
 * rules out theme colours (a dynamic-colour primary is often a pale pastel that
 * disappears over a bright image) and hairline strokes. So the outlines are
 * drawn as a light line inside a dark halo, the selection uses a fixed vivid
 * hue, and while a selection is live the image is dimmed behind it.
 */
@Composable
fun OcrTextLayer(
    layout: OcrLayout,
    containerSize: IntSize,
    showOutlines: Boolean,
    zoom: () -> Float,
    modifier: Modifier = Modifier,
) {
    if (layout.boxes.isEmpty() || containerSize.width <= 0 || containerSize.height <= 0) return

    val measurer = rememberTextMeasurer()
    val style = remember { TextStyle(color = Color.Transparent, fontSize = BASE_FONT_SIZE) }
    val placements = remember(layout, containerSize, style, measurer) {
        placeBoxes(layout, containerSize) { text ->
            measurer.measure(text = text, style = style, maxLines = 1, softWrap = false).size
        }
    }
    if (placements.isEmpty()) return

    val selectionColors = remember {
        TextSelectionColors(
            handleColor = SELECTION_COLOR,
            backgroundColor = SELECTION_COLOR.copy(alpha = 0.5f),
        )
    }

    // Compose keeps selection state private to SelectionContainer, but it drives
    // the floating toolbar from it — so a toolbar that reports when it's asked to
    // appear tells us a selection is live.
    var toolbarShown by remember { mutableStateOf(false) }
    val platformToolbar = LocalTextToolbar.current
    val toolbar = remember(platformToolbar) {
        SelectionAwareTextToolbar(platformToolbar) { toolbarShown = it }
    }
    // Dragging a handle hides and re-shows the toolbar, so the dim can't follow it
    // directly or it would strobe during the one gesture it exists to support.
    var dimImage by remember { mutableStateOf(false) }
    LaunchedEffect(toolbarShown) {
        if (toolbarShown) {
            dimImage = true
        } else {
            delay(DIM_LINGER_MS)
            dimImage = false
        }
    }

    val density = LocalDensity.current

    Box(modifier = modifier) {
        // Both of these sit under the selectable text in z-order so they can never
        // intercept a touch (a Canvas takes no pointer input either way).
        FadeVisibility(visible = dimImage) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color = Color.Black.copy(alpha = 0.4f))
            }
        }

        // Shown with the metadata card, and whenever text is being selected, which
        // makes the feature discoverable without adding a control.
        FadeVisibility(visible = showOutlines || dimImage) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // The whole layer is inside the image's zoom transform, so the
                // strokes are pre-divided by it to stay a constant thickness
                // on screen instead of ballooning as the photo is magnified.
                val halo = OUTLINE_HALO.toPx() / zoom()
                val line = OUTLINE_STROKE.toPx() / zoom()
                placements.forEach { p ->
                    // Wide dark stroke first, narrow light stroke centred inside it:
                    // one of the two always contrasts, whatever is underneath.
                    drawPath(
                        path = p.outline,
                        color = Color.Black.copy(alpha = 0.55f),
                        style = Stroke(width = halo),
                    )
                    drawPath(
                        path = p.outline,
                        color = Color.White.copy(alpha = 0.9f),
                        style = Stroke(width = line),
                    )
                }
            }
        }

        CompositionLocalProvider(
            LocalTextSelectionColors provides selectionColors,
            LocalTextToolbar provides toolbar,
        ) {
            SelectionContainer {
                Box(modifier = Modifier.fillMaxSize()) {
                    placements.forEach { p ->
                        BasicText(
                            text = p.text,
                            style = style,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                            modifier = Modifier
                                .offset { IntOffset(p.origin.x.roundToInt(), p.origin.y.roundToInt()) }
                                // Laid out at the glyph run's natural size, then
                                // stretched onto the quad by the layer transform
                                // below. An OCR box's aspect ratio never matches a
                                // font's, so a font size alone can only match the
                                // box's width or its height, not both.
                                .requiredSize(
                                    width = with(density) { p.measured.width.toDp() },
                                    height = with(density) { p.measured.height.toDp() },
                                )
                                .graphicsLayer {
                                    // Scale is applied to the content before the
                                    // rotation, so the run is stretched along its
                                    // own axes and then turned onto the quad.
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    scaleX = p.scaleX
                                    scaleY = p.scaleY
                                    rotationZ = p.angleDegrees
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Forwards everything to the real [TextToolbar] while reporting whether it is
 * being shown, which is the only signal [SelectionContainer] gives out about
 * whether anything is currently selected.
 */
private class SelectionAwareTextToolbar(
    private val delegate: TextToolbar,
    private val onShownChange: (Boolean) -> Unit,
) : TextToolbar {
    override val status: TextToolbarStatus get() = delegate.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        onShownChange(true)
        delegate.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        onShownChange(false)
        delegate.hide()
    }
}

/** One OCR line resolved to on-screen pixels plus the stretch onto its quad. */
private data class OcrPlacement(
    val text: String,
    /** The quad's first corner: where the text run starts. */
    val origin: Offset,
    val outline: Path,
    val measured: IntSize,
    val scaleX: Float,
    val scaleY: Float,
    /** Clockwise screen-space rotation of the run, from the quad's top edge. */
    val angleDegrees: Float,
)

private fun placeBoxes(
    layout: OcrLayout,
    containerSize: IntSize,
    measure: (String) -> IntSize,
): List<OcrPlacement> {
    // ContentScale.Fit + Alignment.Center: uniform scale, letterboxed and centred.
    val transform = imageFitTransform(layout.w, layout.h, containerSize) ?: return emptyList()
    val fit = transform.scale
    val originX = transform.originX
    val originY = transform.originY

    return layout.boxes.mapNotNull { box ->
        // The letterbox is a uniform scale, so mapping the corners through it
        // leaves the quad's angle intact. Rows stored before the detector
        // returned quads fall back to their axis-aligned box.
        val quad = box.quad
        val corners = if (quad != null && quad.size == 8) {
            List(4) { Offset(originX + quad[it * 2] * fit, originY + quad[it * 2 + 1] * fit) }
        } else {
            listOf(
                Offset(originX + box.left * fit, originY + box.top * fit),
                Offset(originX + box.right * fit, originY + box.top * fit),
                Offset(originX + box.right * fit, originY + box.bottom * fit),
                Offset(originX + box.left * fit, originY + box.bottom * fit),
            )
        }

        val runX = corners[1].x - corners[0].x
        val runY = corners[1].y - corners[0].y
        val width = hypot(runX, runY)
        val height = hypot(corners[3].x - corners[0].x, corners[3].y - corners[0].y)
        if (width <= 0f || height <= 0f) return@mapNotNull null
        // Compose concatenates each selectable's text with no separator, so every
        // line has to carry its own newline or a multi-line copy comes out as one
        // run-together string. maxLines = 1 keeps it a single line tall.
        val text = box.text + "\n"
        val measured = measure(text)
        if (measured.width <= 0 || measured.height <= 0) return@mapNotNull null
        OcrPlacement(
            text = text,
            origin = corners[0],
            outline = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                lineTo(corners[1].x, corners[1].y)
                lineTo(corners[2].x, corners[2].y)
                lineTo(corners[3].x, corners[3].y)
                close()
            },
            measured = measured,
            scaleX = width / measured.width,
            scaleY = height / measured.height,
            angleDegrees = (atan2(runY, runX) * 180f / PI).toFloat(),
        )
    }
}

private val BASE_FONT_SIZE = 16.sp
private val OUTLINE_STROKE = 1.dp
private val OUTLINE_HALO = 3.dp
private val SELECTION_COLOR = Color(0xFF448AFF)
private const val DIM_LINGER_MS = 250L
