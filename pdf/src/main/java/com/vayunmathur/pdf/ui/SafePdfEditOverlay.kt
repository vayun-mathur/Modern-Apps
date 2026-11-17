package com.vayunmathur.pdf.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.toArgb
import com.vayunmathur.pdf.platform.SafeAnnotation
import com.vayunmathur.pdf.platform.SafePdfDocument
import com.vayunmathur.pdf.platform.addHighlight
import com.vayunmathur.pdf.platform.addInk
import com.vayunmathur.pdf.platform.addOval
import com.vayunmathur.pdf.platform.addPoly
import com.vayunmathur.pdf.platform.addRect
import com.vayunmathur.pdf.platform.addRedaction
import com.vayunmathur.pdf.platform.addTextMarkup
import com.vayunmathur.pdf.platform.moveAnnotation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Edit-mode gesture layer: tap-to-create/select plus drag-to-draw/move.
 * The in-progress preview Canvas lives in [SafePdfEditPreview].
 */
@Composable
internal fun EditOverlay(
    annotations: List<SafeAnnotation>,
    selected: Long?,
    tool: EditTool,
    shape: ShapeKind,
    markup: MarkupKind,
    color: Color,
    strokeWidth: Float,
    ch: Float,
    scale: Float,
    toPage: (Offset) -> Offset,
    document: SafePdfDocument,
    index: Int,
    scope: CoroutineScope,
    onSelect: (Long?) -> Unit,
    onEdited: () -> Unit,
    onCreated: (Long) -> Unit,
    onMoved: (Long, List<Float>, List<Float>) -> Unit,
    onStartText: (TextSession) -> Unit,
    onRequestImage: (Offset) -> Unit,
    onRequestNote: (Offset) -> Unit,
    onRequestCallout: (Offset, Offset) -> Unit,
    polyDraft: PolyDraft?,
    onAddPolyPoint: (Offset) -> Unit,
) {
    // In-progress drag shape in screen space.
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var inkPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // Accumulated move delta for the selected annotation (screen space).
    var moveDelta by remember { mutableStateOf(Offset.Zero) }

    // Keep annotation data stable for the gesture coroutine via updated states, so
    // the gesture is NOT cancelled when selected or annotations identity changes
    // (previously pointerInput(tool, selected, annotations) caused annotation drag
    // to cancel immediately after selecting at touchSlop threshold).
    val latestAnnotations by rememberUpdatedState(annotations)
    val latestSelected by rememberUpdatedState(selected)
    val latestToPage by rememberUpdatedState(toPage)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnStartText by rememberUpdatedState(onStartText)
    val latestOnRequestImage by rememberUpdatedState(onRequestImage)
    val latestOnRequestNote by rememberUpdatedState(onRequestNote)
    val latestOnRequestCallout by rememberUpdatedState(onRequestCallout)
    val latestOnAddPolyPoint by rememberUpdatedState(onAddPolyPoint)
    val latestOnCreated by rememberUpdatedState(onCreated)
    val latestOnEdited by rememberUpdatedState(onEdited)
    val latestOnMoved by rememberUpdatedState(onMoved)
    val latestDocument by rememberUpdatedState(document)
    val latestScope by rememberUpdatedState(scope)
    val latestScale by rememberUpdatedState(scale)
    val latestShape by rememberUpdatedState(shape)
    val latestMarkup by rememberUpdatedState(markup)
    val latestColor by rememberUpdatedState(color)
    val latestStroke by rememberUpdatedState(strokeWidth)
    val currentTool by rememberUpdatedState(tool)

    fun annotAt(screen: Offset): SafeAnnotation? {
        val p = latestToPage(screen)
        return latestAnnotations.lastOrNull { p.x in it.x0..it.x1 && p.y in it.y0..it.y1 }
    }

    val gestures = Modifier.pointerInput(currentTool, index) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            val hitAtDown = annotAt(start)
            val selectMove = currentTool == EditTool.SELECT && hitAtDown != null
            val blockScroll =
                currentTool == EditTool.HIGHLIGHT || currentTool == EditTool.MARKUP || currentTool == EditTool.SHAPE ||
                    currentTool == EditTool.LINE || currentTool == EditTool.CALLOUT || currentTool == EditTool.REDACT ||
                    currentTool == EditTool.DRAW || selectMove
            // SELECT defers consume until dragging to allow pinch-zoom over annotation
            if (blockScroll && currentTool != EditTool.SELECT) {
                down.consume()
            }
            dragStart = start
            dragCurrent = start
            if (currentTool == EditTool.DRAW) inkPoints = listOf(start)
            if (currentTool == EditTool.SELECT) moveDelta = Offset.Zero

            var dragging = false
            var lastPos = start
            var draggedId: Long? = hitAtDown?.id
            var draggedInitRect: List<Float>? = hitAtDown?.let { listOf(it.x0, it.y0, it.x1, it.y1) }
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val pos = change.position
                if (!dragging && (pos - start).getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                    if (selectMove) {
                        latestOnSelect(draggedId)
                        change.consume()
                        down.consume()
                    }
                }
                if (dragging) {
                    // Don't consume Select drags on empty space, so they scroll.
                    val consume = currentTool != EditTool.SELECT || selectMove
                    if (consume) change.consume()
                    when (currentTool) {
                        EditTool.HIGHLIGHT, EditTool.MARKUP, EditTool.SHAPE, EditTool.LINE, EditTool.CALLOUT, EditTool.REDACT -> dragCurrent = pos
                        EditTool.DRAW -> { dragCurrent = pos; inkPoints = inkPoints + pos }
                        EditTool.SELECT -> if (selectMove) moveDelta += (pos - lastPos)
                        else -> {}
                    }
                }
                lastPos = pos
            }

            if (!dragging) {
                when (currentTool) {
                    EditTool.TEXT -> {
                        val p = latestToPage(start)
                        latestOnStartText(
                            TextSession(
                                page = index,
                                origin = p,
                                size = 14f,
                                color = latestColor.toArgb(),
                                annotId = null,
                                value = TextFieldValue("Text", TextRange(0, 4)),
                            )
                        )
                    }
                    EditTool.IMAGE -> latestOnRequestImage(latestToPage(start))
                    EditTool.NOTE -> latestOnRequestNote(latestToPage(start))
                    EditTool.POLYLINE, EditTool.BEZIER -> latestOnAddPolyPoint(latestToPage(start))
                    EditTool.SELECT -> {
                        val hit = annotAt(start)
                        val sel = latestSelected
                        if (hit != null && hit.id == sel && hit.subtype == 1) {
                            val sz = ((hit.y1 - hit.y0) / 1.3f).coerceIn(6f, 72f)
                            latestOnStartText(
                                TextSession(
                                    page = index,
                                    origin = Offset(hit.x0, hit.y1),
                                    size = sz,
                                    color = hit.color,
                                    annotId = hit.id,
                                    value = TextFieldValue(hit.contents, TextRange(0, hit.contents.length)),
                                )
                            )
                        } else {
                            latestOnSelect(hit?.id)
                        }
                    }
                    else -> {}
                }
            } else {
                val s = dragStart
                val e = dragCurrent
                val doc = latestDocument
                val scp = latestScope
                when (currentTool) {
                    EditTool.HIGHLIGHT -> if (s != null && e != null) {
                        val a = latestToPage(s); val b = latestToPage(e)
                        scp.launch {
                            val id = doc.addHighlight(index, a.x, a.y, b.x, b.y, latestColor.toArgb())
                            latestOnCreated(id); latestOnEdited()
                        }
                    }
                    EditTool.MARKUP -> if (s != null && e != null) {
                        val a = latestToPage(s); val b = latestToPage(e)
                        scp.launch {
                            val mk = latestMarkup
                            val id = if (mk == MarkupKind.HIGHLIGHT) {
                                doc.addHighlight(index, a.x, a.y, b.x, b.y, latestColor.toArgb())
                            } else {
                                val kind = when (mk) {
                                    MarkupKind.STRIKEOUT -> 1
                                    MarkupKind.SQUIGGLY -> 2
                                    else -> 0
                                }
                                doc.addTextMarkup(index, a.x, a.y, b.x, b.y, latestColor.toArgb(), kind)
                            }
                            latestOnCreated(id); latestOnEdited()
                        }
                    }
                    EditTool.CALLOUT -> if (s != null && e != null) {
                        latestOnRequestCallout(latestToPage(s), latestToPage(e))
                    }
                    EditTool.SHAPE -> if (s != null && e != null) {
                        val rect = Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
                        val shp = latestShape
                        val lineWidth = if (shp.isFill) 0f else latestStroke
                        when (shp.geom) {
                            ShapeGeom.RECT -> {
                                val a = latestToPage(Offset(rect.left, rect.top)); val b = latestToPage(Offset(rect.right, rect.bottom))
                                scp.launch {
                                    val id = doc.addRect(index, a.x, a.y, b.x, b.y, latestColor.toArgb(), lineWidth, shp.isFill)
                                    latestOnCreated(id); latestOnEdited()
                                }
                            }
                            ShapeGeom.OVAL -> {
                                val a = latestToPage(Offset(rect.left, rect.top)); val b = latestToPage(Offset(rect.right, rect.bottom))
                                scp.launch {
                                    val id = doc.addOval(index, a.x, a.y, b.x, b.y, latestColor.toArgb(), lineWidth, shp.isFill)
                                    latestOnCreated(id); latestOnEdited()
                                }
                            }
                            ShapeGeom.POLYGON -> {
                                val unit = shp.unitPolygon()
                                val flat = FloatArray(unit.size * 2)
                                unit.forEachIndexed { i, u ->
                                    val pp = latestToPage(mapUnit(u, rect)); flat[i * 2] = pp.x; flat[i * 2 + 1] = pp.y
                                }
                                scp.launch {
                                    val id = doc.addPoly(index, flat, latestColor.toArgb(), lineWidth, shp.isFill, closed = true)
                                    latestOnCreated(id); latestOnEdited()
                                }
                            }
                        }
                    }
                    EditTool.LINE -> if (s != null && e != null) {
                        val a = latestToPage(s); val b = latestToPage(e)
                        scp.launch {
                            val id = doc.addPoly(index, floatArrayOf(a.x, a.y, b.x, b.y), latestColor.toArgb(), latestStroke, fill = false, closed = false)
                            latestOnCreated(id); latestOnEdited()
                        }
                    }
                    EditTool.REDACT -> if (s != null && e != null) {
                        val a = latestToPage(Offset(minOf(s.x, e.x), minOf(s.y, e.y)))
                        val b = latestToPage(Offset(maxOf(s.x, e.x), maxOf(s.y, e.y)))
                        scp.launch {
                            val id = doc.addRedaction(index, a.x, a.y, b.x, b.y)
                            latestOnCreated(id); latestOnEdited()
                        }
                    }
                    EditTool.DRAW -> {
                        val pts = inkPoints
                        if (pts.size >= 2) {
                            val flat = FloatArray(pts.size * 2)
                            pts.forEachIndexed { i, o ->
                                val pp = latestToPage(o); flat[i * 2] = pp.x; flat[i * 2 + 1] = pp.y
                            }
                            scp.launch {
                                val id = doc.addInk(index, latestColor.toArgb(), latestStroke, flat)
                                latestOnCreated(id); latestOnEdited()
                            }
                        }
                    }
                    EditTool.SELECT -> if (selectMove) {
                        val id = draggedId
                        if (id != null) {
                            val dx = moveDelta.x / latestScale
                            val dy = -moveDelta.y / latestScale
                            if (dx != 0f || dy != 0f) {
                                val initR = draggedInitRect
                                if (initR != null) {
                                    val newR = listOf(initR[0] + dx, initR[1] + dy, initR[2] + dx, initR[3] + dy)
                                    scp.launch {
                                        doc.moveAnnotation(index, id, newR[0], newR[1], newR[2], newR[3])
                                        latestOnMoved(id, initR, newR)
                                        latestOnEdited()
                                    }
                                } else {
                                    val a = latestAnnotations.firstOrNull { it.id == id }
                                    if (a != null) {
                                        val oldR = listOf(a.x0, a.y0, a.x1, a.y1)
                                        val newR = listOf(a.x0 + dx, a.y0 + dy, a.x1 + dx, a.y1 + dy)
                                        scp.launch {
                                            doc.moveAnnotation(index, id, newR[0], newR[1], newR[2], newR[3])
                                            latestOnMoved(id, oldR, newR)
                                            latestOnEdited()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
            dragStart = null
            dragCurrent = null
            if (currentTool == EditTool.DRAW) inkPoints = emptyList()
            moveDelta = Offset.Zero
        }
    }

    SafePdfEditPreview(
        modifier = Modifier.fillMaxSize().then(gestures),
        annotations = latestAnnotations,
        selected = latestSelected,
        tool = tool,
        shape = shape,
        markup = markup,
        color = color,
        ch = ch,
        scale = scale,
        dragStart = dragStart,
        dragCurrent = dragCurrent,
        moveDelta = moveDelta,
        inkPoints = inkPoints,
        polyDraft = polyDraft,
    )
}
