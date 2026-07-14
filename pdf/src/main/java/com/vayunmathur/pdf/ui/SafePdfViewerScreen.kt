package com.vayunmathur.pdf.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.pdf.util.SafeOutlineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.util.PdfPrimitive
import com.vayunmathur.pdf.util.SafeAnnotation
import com.vayunmathur.pdf.util.SafeFormField
import com.vayunmathur.pdf.util.SafeLink
import com.vayunmathur.pdf.util.SafePdfDocument
import com.vayunmathur.pdf.util.PdfStateStore
import com.vayunmathur.pdf.util.SafePdfPage
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.ByteArrayOutputStream

private sealed interface LoadState {
    data object Loading : LoadState
    data object Error : LoadState
    data class Loaded(val document: SafePdfDocument) : LoadState
}

/** Editing tools. */
private enum class EditTool { SELECT, TEXT, HIGHLIGHT, MARKUP, DRAW, SHAPE, LINE, POLYLINE, BEZIER, NOTE, CALLOUT, REDACT, IMAGE }

/** Text-markup variants for the [EditTool.MARKUP] tool. */
private enum class MarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY }

@DrawableRes
private fun MarkupKind.icon(): Int = when (this) {
    MarkupKind.HIGHLIGHT -> R.drawable.ic_tool_highlight
    MarkupKind.UNDERLINE -> R.drawable.ic_markup_underline
    MarkupKind.STRIKEOUT -> R.drawable.ic_markup_strikeout
    MarkupKind.SQUIGGLY -> R.drawable.ic_markup_squiggly
}

private fun MarkupKind.label(): String = when (this) {
    MarkupKind.HIGHLIGHT -> "Highlight"
    MarkupKind.UNDERLINE -> "Underline"
    MarkupKind.STRIKEOUT -> "Strikeout"
    MarkupKind.SQUIGGLY -> "Squiggly"
}

/** Closed-shape variants for the [EditTool.SHAPE] tool (dragged bounding box). */
private enum class ShapeKind {
    RECT_OUTLINE, RECT_FILL,
    ROUNDRECT_OUTLINE, ROUNDRECT_FILL,
    OVAL_OUTLINE, OVAL_FILL,
    TRIANGLE_OUTLINE, TRIANGLE_FILL,
    DIAMOND_OUTLINE, DIAMOND_FILL,
    PENTAGON_OUTLINE, PENTAGON_FILL,
    HEXAGON_OUTLINE, HEXAGON_FILL,
    STAR_OUTLINE, STAR_FILL,
    ARROW_OUTLINE, ARROW_FILL,
}

private enum class ShapeGeom { RECT, OVAL, POLYGON }

private val ShapeKind.isFill: Boolean get() = name.endsWith("_FILL")

private val ShapeKind.geom: ShapeGeom
    get() = when (this) {
        ShapeKind.RECT_OUTLINE, ShapeKind.RECT_FILL -> ShapeGeom.RECT
        ShapeKind.OVAL_OUTLINE, ShapeKind.OVAL_FILL -> ShapeGeom.OVAL
        else -> ShapeGeom.POLYGON
    }

/**
 * Vertices for [ShapeGeom.POLYGON] shapes in the unit square (x,y in 0..1,
 * y-down), to be scaled into the dragged bounding box. Empty for rect/oval.
 */
private fun ShapeKind.unitPolygon(): List<Offset> = when (this) {
    ShapeKind.TRIANGLE_OUTLINE, ShapeKind.TRIANGLE_FILL ->
        listOf(Offset(0.5f, 0f), Offset(1f, 1f), Offset(0f, 1f))
    ShapeKind.DIAMOND_OUTLINE, ShapeKind.DIAMOND_FILL ->
        listOf(Offset(0.5f, 0f), Offset(1f, 0.5f), Offset(0.5f, 1f), Offset(0f, 0.5f))
    ShapeKind.PENTAGON_OUTLINE, ShapeKind.PENTAGON_FILL -> regularPolygonUnit(5)
    ShapeKind.HEXAGON_OUTLINE, ShapeKind.HEXAGON_FILL -> regularPolygonUnit(6)
    ShapeKind.STAR_OUTLINE, ShapeKind.STAR_FILL -> starUnit(5, 0.5f, 0.22f)
    ShapeKind.ARROW_OUTLINE, ShapeKind.ARROW_FILL -> listOf(
        Offset(0f, 0.3f), Offset(0.6f, 0.3f), Offset(0.6f, 0.08f), Offset(1f, 0.5f),
        Offset(0.6f, 0.92f), Offset(0.6f, 0.7f), Offset(0f, 0.7f),
    )
    ShapeKind.ROUNDRECT_OUTLINE, ShapeKind.ROUNDRECT_FILL -> roundRectUnit(0.2f, 5)
    else -> emptyList()
}

/** [n]-gon inscribed in the unit square, first vertex at top, y-down. */
private fun regularPolygonUnit(n: Int): List<Offset> = (0 until n).map { k ->
    val a = -Math.PI / 2 + 2 * Math.PI * k / n
    Offset(0.5f + 0.5f * kotlin.math.cos(a).toFloat(), 0.5f + 0.5f * kotlin.math.sin(a).toFloat())
}

/** [points]-pointed star with [outer]/[inner] radii, first point at top, y-down. */
private fun starUnit(points: Int, outer: Float, inner: Float): List<Offset> =
    (0 until points * 2).map { k ->
        val r = if (k % 2 == 0) outer else inner
        val a = -Math.PI / 2 + Math.PI * k / points
        Offset(0.5f + r * kotlin.math.cos(a).toFloat(), 0.5f + r * kotlin.math.sin(a).toFloat())
    }

/** Rounded rectangle perimeter as a polygon, corner [radius] in unit space with
 * [seg] segments per corner. */
private fun roundRectUnit(radius: Float, seg: Int): List<Offset> {
    val r = radius.coerceIn(0f, 0.5f)
    val pts = mutableListOf<Offset>()
    // Corner centers, and arc start angles (clockwise, y-down).
    val corners = listOf(
        Triple(1f - r, r, -Math.PI / 2),      // top-right
        Triple(1f - r, 1f - r, 0.0),          // bottom-right
        Triple(r, 1f - r, Math.PI / 2),       // bottom-left
        Triple(r, r, Math.PI),                // top-left
    )
    for ((cx, cy, start) in corners) {
        for (i in 0..seg) {
            val a = start + (Math.PI / 2) * i / seg
            pts += Offset(cx + r * kotlin.math.cos(a).toFloat(), cy + r * kotlin.math.sin(a).toFloat())
        }
    }
    return pts
}

/** Map a unit-square point into the screen-space bounding box [rect]. */
private fun mapUnit(u: Offset, rect: Rect): Offset =
    Offset(rect.left + u.x * rect.width, rect.top + u.y * rect.height)

/**
 * Smooth an open sequence of [points] into a flattened curve passing through
 * them (Catmull-Rom → cubic Bézier, sampled), for the Bézier tool.
 */
private fun flattenSmooth(points: List<Offset>): List<Offset> {
    if (points.size < 3) return points
    val out = mutableListOf(points.first())
    val steps = 16
    for (i in 0 until points.size - 1) {
        val p0 = points[if (i == 0) 0 else i - 1]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[if (i + 2 <= points.size - 1) i + 2 else points.size - 1]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        for (s in 1..steps) {
            val t = s.toFloat() / steps
            val mt = 1f - t
            val x = mt * mt * mt * p1.x + 3 * mt * mt * t * c1.x + 3 * mt * t * t * c2.x + t * t * t * p2.x
            val y = mt * mt * mt * p1.y + 3 * mt * mt * t * c1.y + 3 * mt * t * t * c2.y + t * t * t * p2.y
            out += Offset(x, y)
        }
    }
    return out
}

@DrawableRes
private fun ShapeKind.icon(): Int = when (this) {
    ShapeKind.RECT_OUTLINE -> R.drawable.ic_tool_rect
    ShapeKind.RECT_FILL -> R.drawable.ic_shape_rect_fill
    ShapeKind.ROUNDRECT_OUTLINE -> R.drawable.ic_shape_roundrect_outline
    ShapeKind.ROUNDRECT_FILL -> R.drawable.ic_shape_roundrect_fill
    ShapeKind.OVAL_OUTLINE -> R.drawable.ic_shape_oval_outline
    ShapeKind.OVAL_FILL -> R.drawable.ic_shape_oval_fill
    ShapeKind.TRIANGLE_OUTLINE -> R.drawable.ic_shape_triangle_outline
    ShapeKind.TRIANGLE_FILL -> R.drawable.ic_shape_triangle_fill
    ShapeKind.DIAMOND_OUTLINE -> R.drawable.ic_shape_diamond_outline
    ShapeKind.DIAMOND_FILL -> R.drawable.ic_shape_diamond_fill
    ShapeKind.PENTAGON_OUTLINE -> R.drawable.ic_shape_pentagon_outline
    ShapeKind.PENTAGON_FILL -> R.drawable.ic_shape_pentagon_fill
    ShapeKind.HEXAGON_OUTLINE -> R.drawable.ic_shape_hexagon_outline
    ShapeKind.HEXAGON_FILL -> R.drawable.ic_shape_hexagon_fill
    ShapeKind.STAR_OUTLINE -> R.drawable.ic_shape_star_outline
    ShapeKind.STAR_FILL -> R.drawable.ic_shape_star_fill
    ShapeKind.ARROW_OUTLINE -> R.drawable.ic_shape_arrow_outline
    ShapeKind.ARROW_FILL -> R.drawable.ic_shape_arrow_fill
}

private fun ShapeKind.label(): String {
    val base = when (geom) {
        ShapeGeom.RECT -> if (this == ShapeKind.ROUNDRECT_OUTLINE || this == ShapeKind.ROUNDRECT_FILL) "Rounded rectangle" else "Rectangle"
        ShapeGeom.OVAL -> "Ellipse"
        ShapeGeom.POLYGON -> when (this) {
            ShapeKind.ROUNDRECT_OUTLINE, ShapeKind.ROUNDRECT_FILL -> "Rounded rectangle"
            ShapeKind.TRIANGLE_OUTLINE, ShapeKind.TRIANGLE_FILL -> "Triangle"
            ShapeKind.DIAMOND_OUTLINE, ShapeKind.DIAMOND_FILL -> "Diamond"
            ShapeKind.PENTAGON_OUTLINE, ShapeKind.PENTAGON_FILL -> "Pentagon"
            ShapeKind.HEXAGON_OUTLINE, ShapeKind.HEXAGON_FILL -> "Hexagon"
            ShapeKind.STAR_OUTLINE, ShapeKind.STAR_FILL -> "Star"
            ShapeKind.ARROW_OUTLINE, ShapeKind.ARROW_FILL -> "Arrow"
            else -> "Shape"
        }
    }
    return if (isFill) "$base (filled)" else base
}

/** In-progress polyline / Bézier being built by tapping points. */
private data class PolyDraft(val page: Int, val points: List<Offset>, val bezier: Boolean)

/**
 * A reversible edit. ADDED (undo detaches), REMOVED (undo re-attaches), or MOVED
 * (undo restores [oldRect], redo restores [newRect]). Rects are page-space
 * [x0,y0,x1,y1].
 */
private enum class EditKind { ADDED, REMOVED, MOVED }

private data class EditAction(
    val page: Int,
    val annotId: Long,
    val kind: EditKind,
    val oldRect: List<Float>? = null,
    val newRect: List<Float>? = null,
)

/**
 * Clamp the zoom [pan] (screen-pixel translation) so the content, scaled by
 * [zoom] around its center, can't be dragged past the viewport ([size]) edges.
 * At zoom 1 the range is zero, so the page stays put.
 */
private fun clampPan(pan: Offset, zoom: Float, size: IntSize): Offset {
    val maxX = (size.width * (zoom - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (size.height * (zoom - 1f) / 2f).coerceAtLeast(0f)
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

/**
 * An in-progress inline text edit. [origin] is the top of the text box in page
 * space; [annotId] is set when editing an existing FreeText, null for a new one.
 */
private data class TextSession(
    val page: Int,
    val origin: Offset,
    val size: Float,
    val color: Int,
    val annotId: Long?,
    val value: TextFieldValue,
)

/**
 * Read-only + overlay-editing PDF viewer that never touches the system PDF
 * stack: pages are parsed in Rust ([SafePdfDocument]) and drawn from plain
 * primitives on a Compose [Canvas]. Editing (annotations, form filling) is
 * written back through lopdf and saved via SAF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafePdfViewerScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Password handling for encrypted PDFs.
    var password by remember(uri) { mutableStateOf<String?>(null) }
    var needsPassword by remember(uri) { mutableStateOf(false) }
    var pwError by remember(uri) { mutableStateOf(false) }

    val loadState by produceState<LoadState>(LoadState.Loading, uri, password) {
        value = LoadState.Loading
        val doc = SafePdfDocument.open(context, uri, password)
        value = if (doc != null) {
            needsPassword = false
            LoadState.Loaded(doc)
        } else {
            when (SafePdfDocument.passwordState(context, uri)) {
                1 -> { needsPassword = true; pwError = password != null; LoadState.Loading }
                else -> LoadState.Error
            }
        }
    }
    val document = (loadState as? LoadState.Loaded)?.document
    DisposableEffect(document) { onDispose { document?.close() } }

    if (needsPassword) {
        var pwInput by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { onBack() },
            title = { Text("Password required") },
            text = {
                Column {
                    if (pwError) Text("Incorrect password", color = MaterialTheme.colorScheme.error)
                    TextField(
                        value = pwInput,
                        onValueChange = { pwInput = it },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        placeholder = { Text("Password") },
                    )
                }
            },
            confirmButton = { TextButton({ needsPassword = false; password = pwInput }) { Text("Open") } },
            dismissButton = { TextButton({ onBack() }) { Text("Cancel") } },
        )
    }

    // Prebuild the search index in the background so the first query is instant.
    LaunchedEffect(document) { document?.prewarmSearch() }

    var editMode by remember { mutableStateOf(false) }
    var showSaveMenu by remember { mutableStateOf(false) }
    // Set once any edit is made; keeps the Save control visible thereafter.
    var dirty by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(EditTool.SELECT) }
    var shape by remember { mutableStateOf(ShapeKind.RECT_OUTLINE) }
    var markup by remember { mutableStateOf(MarkupKind.HIGHLIGHT) }
    // Pending note/callout awaiting text entry: page + point(s).
    var pendingNote by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    var pendingCallout by remember { mutableStateOf<Triple<Int, Offset, Offset>?>(null) }
    // In-progress polyline/Bézier (built by tapping points; committed via the check button).
    var polyDraft by remember { mutableStateOf<PolyDraft?>(null) }
    var color by remember { mutableStateOf(Color.Red) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var strokeWidth by remember { mutableFloatStateOf(2f) }
    var showStyle by remember { mutableStateOf(false) }
    // Undo/redo of annotation add/remove/move (backed by native ops).
    val undoStack = remember { mutableStateListOf<EditAction>() }
    val redoStack = remember { mutableStateListOf<EditAction>() }
    // Set by non-undoable edits (forms, flatten, redactions) so Save still shows.
    var nonUndoDirty by remember { mutableStateOf(false) }
    // Jump-to-page dialog.
    var showJump by remember { mutableStateOf(false) }
    // Global refresh version for document-wide ops (redactions).
    var selectText by remember { mutableStateOf(false) }
    var pageCount by remember(document) { mutableIntStateOf(document?.pageCount ?: 0) }
    var pageMgrVersion by remember { mutableIntStateOf(0) }
    // Per-page render version: bumping one page's entry re-renders ONLY that page,
    // so an edit doesn't force every visible page to re-decode.
    val pageVersions = remember { mutableStateMapOf<Int, Int>() }
    var selected by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    // Re-render the edited page and record that an edit was made.
    val markEdited: (Int) -> Unit = { page ->
        pageVersions[page] = (pageVersions[page] ?: 0) + 1
        dirty = true
    }
    // Register a freshly created annotation for undo.
    val registerCreated: (Int, Long) -> Unit = { page, id ->
        if (id != 0L) {
            undoStack.add(EditAction(page, id, EditKind.ADDED))
            redoStack.clear()
        }
    }
    val undo: () -> Unit = {
        val a = undoStack.removeLastOrNull()
        val doc = document
        if (a != null && doc != null) {
            scope.launch {
                when (a.kind) {
                    EditKind.ADDED -> doc.detachAnnotation(a.page, a.annotId)
                    EditKind.REMOVED -> doc.reattachAnnotation(a.page, a.annotId)
                    EditKind.MOVED -> a.oldRect?.let {
                        doc.moveAnnotation(a.page, a.annotId, it[0], it[1], it[2], it[3])
                    }
                }
                redoStack.add(a); selected = null; markEdited(a.page)
            }
        }
    }
    val redo: () -> Unit = {
        val a = redoStack.removeLastOrNull()
        val doc = document
        if (a != null && doc != null) {
            scope.launch {
                when (a.kind) {
                    EditKind.ADDED -> doc.reattachAnnotation(a.page, a.annotId)
                    EditKind.REMOVED -> doc.detachAnnotation(a.page, a.annotId)
                    EditKind.MOVED -> a.newRect?.let {
                        doc.moveAnnotation(a.page, a.annotId, it[0], it[1], it[2], it[3])
                    }
                }
                undoStack.add(a); selected = null; markEdited(a.page)
            }
        }
    }
    // Record an annotation move for undo/redo.
    val registerMoved: (Int, Long, List<Float>, List<Float>) -> Unit = { page, id, oldR, newR ->
        undoStack.add(EditAction(page, id, EditKind.MOVED, oldR, newR))
        redoStack.clear()
    }

    // Search state.
    val listState = rememberLazyListState()
    // Effective annotation color including the opacity slider's alpha.
    val drawColor = color.copy(alpha = opacity)
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Matches as (pageIndex, page-space rect).
    var matches by remember { mutableStateOf<List<Pair<Int, Rect>>>(emptyList()) }
    var matchIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(query, document, searching) {
        val doc = document
        if (doc == null || !searching || query.isBlank()) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        matches = doc.search(query).map { m ->
            m.page to Rect(m.x0, m.y0, m.x1, m.y1)
        }
        matchIndex = 0
    }

    LaunchedEffect(matchIndex, matches) {
        matches.getOrNull(matchIndex)?.let { listState.animateScrollToItem(it.first) }
    }

    // Outline (bookmarks) + navigation drawer.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val outline by produceState(emptyList<SafeOutlineItem>(), document) {
        value = document?.outline() ?: emptyList()
    }
    // Show the Apply-redactions action only while redaction annotations exist.
    val hasRedactions by produceState(false, undoStack.size, redoStack.size, pageMgrVersion) {
        value = document?.hasRedactions() ?: false
    }

    // Restore last-read page, then persist the first-visible page as it changes.
    LaunchedEffect(document) {
        if (document != null) {
            val p = PdfStateStore.restoreSafePage(context, uri)
            if (p > 0) runCatching { listState.scrollToItem(p) }
        }
    }
    LaunchedEffect(document) {
        if (document == null) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { PdfStateStore.saveSafePage(context, uri, it) }
    }

    // Pinch-to-zoom + pan (two-finger); single-finger still scrolls.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val zoomOld = zoom
        zoom = (zoom * zoomChange).coerceIn(1f, 6f)
        // Keep the content point under the gesture centroid fixed while zooming
        // (the graphicsLayer uses the default center transformOrigin, so pivot
        // relative to the viewport centre), then apply the two-finger drag.
        // panChange/centroid arrive in the layer's (unscaled) coordinate space,
        // so the drag maps to screen pixels via the current zoom.
        val center = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val pivoted = pan - (centroid - center) * (zoom - zoomOld)
        pan = if (zoom > 1f) clampPan(pivoted + panChange * zoom, zoom, viewportSize) else Offset.Zero
    }

    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searching) {
        if (searching) runCatching { searchFocus.requestFocus() }
    }

    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            searching -> { searching = false; query = "" }
            else -> onBack()
        }
    }

    // Inline text-editing session (draws a live text field on the page).
    var textSession by remember { mutableStateOf<TextSession?>(null) }
    // Image-stamp target awaiting a picked image.
    var imageTarget by remember { mutableStateOf<Pair<Int, Offset>?>(null) }

    // Persist a finished text session as a FreeText annotation.
    val commitText: (TextSession?) -> Unit = commit@{ s ->
        if (s == null) return@commit
        val doc = document ?: return@commit
        val txt = s.value.text.trim()
        scope.launch {
            val newId = when {
                s.annotId != null && txt.isEmpty() -> { doc.deleteAnnotation(s.page, s.annotId); 0L }
                s.annotId != null -> { doc.editText(s.page, s.annotId, txt); 0L }
                txt.isNotEmpty() -> doc.addText(
                    s.page, s.origin.x, s.origin.y - s.size * 1.3f, s.origin.x + 220f, s.origin.y,
                    s.color, s.size, txt,
                )
                else -> return@launch
            }
            registerCreated(s.page, newId)
            markEdited(s.page)
        }
    }

    // Finish the in-progress polyline/Bézier: flatten (Bézier) and store as an
    // open PolyLine annotation. Needs >= 2 points; otherwise just discards.
    val commitPoly: () -> Unit = {
        val d = polyDraft
        val doc = document
        if (d != null && doc != null && d.points.size >= 2) {
            val pts = if (d.bezier) flattenSmooth(d.points) else d.points
            val flat = FloatArray(pts.size * 2)
            pts.forEachIndexed { i, p -> flat[i * 2] = p.x; flat[i * 2 + 1] = p.y }
            scope.launch {
                val id = doc.addPoly(d.page, flat, drawColor.toArgb(), strokeWidth, fill = false, closed = false)
                registerCreated(d.page, id)
                markEdited(d.page)
            }
        }
        polyDraft = null
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { outUri ->
        val doc = document
        if (outUri != null && doc != null) {
            scope.launch {
                val bytes = doc.save()
                if (bytes != null) {
                    runCatching {
                        context.contentResolver.openOutputStream(outUri)?.use { it.write(bytes) }
                    }
                }
            }
        }
    }

    var showEncrypt by remember { mutableStateOf(false) }
    var pendingEncryptPw by remember { mutableStateOf<String?>(null) }
    val signLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { outUri ->
        val doc = document
        if (outUri != null && doc != null) scope.launch {
            val signerName = android.os.Build.MODEL ?: "PDF Signer"
            val bytes = doc.sign(signerName)
            withContext(Dispatchers.IO) {
                if (bytes != null) {
                    runCatching { context.contentResolver.openOutputStream(outUri)?.use { it.write(bytes) } }
                }
            }
            android.widget.Toast.makeText(
                context,
                if (bytes != null) "Signed & saved" else "Signing failed",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val encryptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { outUri ->
        val doc = document
        val pw = pendingEncryptPw
        pendingEncryptPw = null
        if (outUri != null && doc != null && pw != null) scope.launch {
            val bytes = doc.saveEncrypted(pw, "")
            if (bytes != null) withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(outUri)?.use { it.write(bytes) } }
            }
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { imgUri ->
        val doc = document
        val target = imageTarget
        imageTarget = null
        if (imgUri != null && doc != null && target != null) {
            scope.launch {
                val jpeg = readAsJpeg(context, imgUri) ?: return@launch
                val (index, pt) = target
                // Default 150pt-wide stamp preserving aspect ratio.
                val w = 150f
                val h = w * jpeg.height / jpeg.width.coerceAtLeast(1)
                doc.addImageStamp(
                    index, pt.x, pt.y - h, pt.x + w, pt.y, jpeg.width, jpeg.height, jpeg.bytes
                ).also { registerCreated(index, it) }
                markEdited(index)
            }
        }
    }

    val shareAction = {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_pdf)))
    }

    // "Save": overwrite the original file in place.
    val saveInPlace: () -> Unit = {
        val doc = document
        if (doc != null) {
            scope.launch {
                val bytes = doc.save()
                val ok = bytes != null && runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
                }.getOrDefault(false)
                android.widget.Toast.makeText(
                    context,
                    if (ok) context.getString(R.string.pdf_saved)
                    else context.getString(R.string.pdf_save_error),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.fillMaxWidth(0.82f)) {
                Text(
                    "Outline",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(outline) { entry ->
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        if (entry.page >= 0) listState.animateScrollToItem(entry.page)
                                        drawerState.close()
                                    }
                                }
                                .padding(
                                    start = (16 + entry.level * 14).dp,
                                    end = 16.dp,
                                    top = 10.dp,
                                    bottom = 10.dp,
                                ),
                        )
                    }
                }
            }
        },
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                            placeholder = { Text(stringResource(R.string.search_label)) },
                            singleLine = true,
                        )
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconNavigation { searching = false; query = "" }
                    } else if (outline.isNotEmpty()) {
                        IconButton({ scope.launch { drawerState.open() } }) { IconMenu() }
                    } else {
                        IconNavigation { onBack() }
                    }
                },
                actions = {
                    if (searching) {
                        if (matches.isNotEmpty()) {
                            Text(
                                "${matchIndex + 1}/${matches.size}",
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            IconButton({ if (matchIndex > 0) matchIndex-- }) {
                                Icon(painterResource(R.drawable.keyboard_arrow_up_24px), null)
                            }
                            IconButton({ if (matchIndex < matches.size - 1) matchIndex++ }) {
                                Icon(painterResource(R.drawable.keyboard_arrow_down_24px), null)
                            }
                        }
                    } else {
                        if (!editMode) {
                            IconButton({ searching = true }) { IconSearch() }
                            IconButton({ showJump = true }) {
                                Icon(painterResource(R.drawable.ic_jump_to_page), contentDescription = "Jump to page")
                            }
                            IconButton({ selectText = !selectText }) {
                                Icon(
                                    painterResource(R.drawable.ic_select_text),
                                    contentDescription = "Select text",
                                    tint = if (selectText) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton({ showEncrypt = true }) {
                                Icon(painterResource(R.drawable.ic_lock), contentDescription = "Encrypt with password")
                            }
                            IconButton({ signLauncher.launch((uri.lastPathSegment ?: "document") + "-signed.pdf") }) {
                                Icon(painterResource(R.drawable.ic_signature), contentDescription = "Sign document")
                            }
                        }
                        if (editMode) {
                            IconButton({ undo() }, enabled = undoStack.isNotEmpty()) {
                                Icon(painterResource(R.drawable.ic_undo), contentDescription = "Undo")
                            }
                            IconButton({ redo() }, enabled = redoStack.isNotEmpty()) {
                                Icon(painterResource(R.drawable.ic_redo), contentDescription = "Redo")
                            }
                        }
                        if (hasRedactions) {
                            IconButton({
                                val doc = document
                                if (doc != null) scope.launch {
                                    doc.applyRedactions(); pageMgrVersion++; nonUndoDirty = true
                                }
                            }) {
                                Icon(painterResource(R.drawable.ic_redact), contentDescription = "Apply redactions")
                            }
                        }
                        IconButton({
                            commitText(textSession); textSession = null
                            commitPoly()
                            editMode = !editMode; selected = null
                        }) {
                            if (editMode) IconVisible() else IconEdit()
                        }
                        if (undoStack.isNotEmpty() || nonUndoDirty) {
                            Box {
                                IconButton({ showSaveMenu = true }) { IconSave() }
                                DropdownMenu(
                                    expanded = showSaveMenu,
                                    onDismissRequest = { showSaveMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Save") },
                                        onClick = { showSaveMenu = false; saveInPlace() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Save as\u2026") },
                                        onClick = {
                                            showSaveMenu = false
                                            saveLauncher.launch(uri.lastPathSegment ?: "edited.pdf")
                                        },
                                    )
                                }
                            }
                        } else {
                            IconButton({ shareAction() }) { IconShare() }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (editMode) {
                EditToolbar(
                    tool = tool,
                    onTool = {
                        // Leaving the multi-point tools finalizes any in-progress draft.
                        if (it != EditTool.POLYLINE && it != EditTool.BEZIER) commitPoly()
                        tool = it; selected = null
                    },
                    shape = shape,
                    onShape = { shape = it; tool = EditTool.SHAPE; selected = null; commitPoly() },
                    markup = markup,
                    onMarkup = { markup = it; tool = EditTool.MARKUP; selected = null; commitPoly() },
                    color = color,
                    onColor = { color = it },
                    onStyle = { showStyle = true },
                    canDelete = selected != null,
                    onDelete = {
                        val sel = selected
                        val doc = document
                        if (sel != null && doc != null) {
                            scope.launch {
                                // Detach (not delete) so it can be undone.
                                doc.detachAnnotation(sel.first, sel.second)
                                undoStack.add(EditAction(sel.first, sel.second, EditKind.REMOVED))
                                redoStack.clear()
                                selected = null
                                markEdited(sel.first)
                            }
                        }
                    },
                    onDuplicate = {
                        val sel = selected
                        val doc = document
                        if (sel != null && doc != null) {
                            scope.launch {
                                val newId = doc.duplicateAnnotation(sel.first, sel.second, 14f, -14f)
                                registerCreated(sel.first, newId)
                                if (newId != 0L) selected = sel.first to newId
                                markEdited(sel.first)
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            val draft = polyDraft
            if (editMode && draft != null) {
                Column {
                    SmallFloatingActionButton(onClick = { polyDraft = null }) {
                        IconClose()
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                    androidx.compose.material3.FloatingActionButton(
                        onClick = { commitPoly() },
                    ) { IconCheck() }
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
        ) {
            when (val state = loadState) {
                LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                LoadState.Error -> Text(
                    text = stringResource(R.string.safe_pdf_error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                is LoadState.Loaded -> LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = pan.x
                            translationY = pan.y
                        }
                        .transformable(transformState, enabled = !editMode || tool == EditTool.SELECT),
                    state = listState,
                ) {
                    items((0 until pageCount).toList()) { index ->
                        val pageHighlights = matches.filter { it.first == index }.map { it.second }
                        val current = matches.getOrNull(matchIndex)
                        val currentHighlight = if (current?.first == index) current.second else null
                        SafePdfPageItem(
                            document = state.document,
                            index = index,
                            version = (pageVersions[index] ?: 0) + pageMgrVersion,
                            editMode = editMode,
                            selectText = selectText,
                            tool = tool,
                            shape = shape,
                            markup = markup,
                            color = drawColor,
                            strokeWidth = strokeWidth,
                            selected = selected?.takeIf { it.first == index }?.second,
                            highlights = pageHighlights,
                            currentHighlight = currentHighlight,
                            scope = scope,
                            onSelect = { annotId -> selected = annotId?.let { index to it } },
                            onEdited = { markEdited(index) },
                            onCreated = { id -> registerCreated(index, id) },
                            onMoved = { id, oldR, newR -> registerMoved(index, id, oldR, newR) },
                            onFormEdited = { markEdited(index); nonUndoDirty = true },
                            onLinkPage = { p -> scope.launch { listState.animateScrollToItem(p.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) } },
                            textSession = textSession?.takeIf { it.page == index },
                            onStartText = { s -> commitText(textSession); textSession = s },
                            onTextChange = { v -> textSession = textSession?.copy(value = v) },
                            onCommitText = { commitText(textSession); textSession = null },
                            onRequestImage = { pt -> imageTarget = index to pt; imageLauncher.launch("image/*") },
                            onRequestNote = { pt -> pendingNote = index to pt },
                            onRequestCallout = { a, b -> pendingCallout = Triple(index, a, b) },
                            polyDraft = polyDraft?.takeIf { it.page == index },
                            onAddPolyPoint = { pt ->
                                val d = polyDraft
                                polyDraft = when {
                                    d == null -> PolyDraft(index, listOf(pt), tool == EditTool.BEZIER)
                                    d.page == index -> d.copy(points = d.points + pt)
                                    else -> d // ignore taps on other pages while drafting
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingNote?.let { (page, pt) ->
        var noteText by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingNote = null },
            title = { Text("Sticky note") },
            text = {
                TextField(value = noteText, onValueChange = { noteText = it }, placeholder = { Text("Note text") })
            },
            confirmButton = {
                TextButton({
                    val doc = document
                    if (doc != null) scope.launch {
                        val id = doc.addNote(page, pt.x, pt.y, drawColor.toArgb(), noteText)
                        registerCreated(page, id); markEdited(page)
                    }
                    pendingNote = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton({ pendingNote = null }) { Text("Cancel") } },
        )
    }

    pendingCallout?.let { (page, a, b) ->
        var calloutText by remember { mutableStateOf("Text") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingCallout = null },
            title = { Text("Callout") },
            text = { TextField(value = calloutText, onValueChange = { calloutText = it }) },
            confirmButton = {
                TextButton({
                    val doc = document
                    if (doc != null) scope.launch {
                        val id = doc.addCallout(page, a.x, a.y, b.x, b.y, drawColor.toArgb(), 14f, calloutText)
                        registerCreated(page, id); markEdited(page)
                    }
                    pendingCallout = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton({ pendingCallout = null }) { Text("Cancel") } },
        )
    }

    if (showStyle) {
        StyleDialog(
            color = color,
            onColor = { color = it },
            opacity = opacity,
            onOpacity = { opacity = it },
            strokeWidth = strokeWidth,
            onWidth = { strokeWidth = it },
            onDismiss = { showStyle = false },
        )
    }

    if (showEncrypt) {
        var pw by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showEncrypt = false },
            title = { Text("Encrypt with password") },
            text = {
                TextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    placeholder = { Text("Password") },
                )
            },
            confirmButton = {
                TextButton({
                    showEncrypt = false
                    if (pw.isNotEmpty()) { pendingEncryptPw = pw; encryptLauncher.launch("encrypted.pdf") }
                }) { Text("Save encrypted") }
            },
            dismissButton = { TextButton({ showEncrypt = false }) { Text("Cancel") } },
        )
    }

    if (showJump) {
        var target by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text("Go to page") },
            text = {
                TextField(
                    value = target,
                    onValueChange = { s -> target = s.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    placeholder = { Text(if (pageCount > 0) "1\u2013$pageCount" else "") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                )
            },
            confirmButton = {
                TextButton({
                    val p = target.toIntOrNull()
                    if (p != null && pageCount > 0) {
                        val idx = p.coerceIn(1, pageCount) - 1
                        scope.launch { listState.animateScrollToItem(idx) }
                    }
                    showJump = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton({ showJump = false }) { Text("Cancel") } },
        )
    }
    }
}

@Composable
private fun SafePdfPageItem(
    document: SafePdfDocument,
    index: Int,
    version: Int,
    editMode: Boolean,
    selectText: Boolean,
    tool: EditTool,
    shape: ShapeKind,
    markup: MarkupKind,
    color: Color,
    strokeWidth: Float,
    selected: Long?,
    highlights: List<Rect>,
    currentHighlight: Rect?,
    scope: CoroutineScope,
    onSelect: (Long?) -> Unit,
    onEdited: () -> Unit,
    onCreated: (Long) -> Unit,
    onMoved: (Long, List<Float>, List<Float>) -> Unit,
    onFormEdited: () -> Unit,
    textSession: TextSession?,
    onStartText: (TextSession) -> Unit,
    onTextChange: (TextFieldValue) -> Unit,
    onCommitText: () -> Unit,
    onRequestImage: (Offset) -> Unit,
    onRequestNote: (Offset) -> Unit,
    onRequestCallout: (Offset, Offset) -> Unit,
    polyDraft: PolyDraft?,
    onAddPolyPoint: (Offset) -> Unit,
    onLinkPage: (Int) -> Unit,
) {
    val page by produceState<SafePdfPage?>(null, document, index, version) {
        value = document.renderPage(index)
    }
    val annotations by produceState(emptyList<SafeAnnotation>(), document, index, version) {
        value = if (editMode) document.annotations(index) else emptyList()
    }
    val formFields by produceState(emptyList<SafeFormField>(), document, index, version) {
        value = if (editMode) document.formFields(index) else emptyList()
    }
    val links by produceState(emptyList<SafeLink>(), document, index, version, editMode) {
        value = if (!editMode) document.links(index) else emptyList()
    }

    val current = page
    val ratio = if (current != null && current.height > 0f) current.width / current.height
    else 612f / 792f

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .aspectRatio(ratio)
            .background(Color.White)
            .clipToBounds()
    ) {
        if (current == null || current.width <= 0f) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }

        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val scale = cw / current.width

        fun toPage(o: Offset) = Offset(o.x / scale, (ch - o.y) / scale)

        // Render the (static) page into its own graphics layer so that overlay
        // redraws while drawing/dragging don't replay every page primitive.
        Canvas(Modifier.fillMaxSize().graphicsLayer { clip = true }) { drawSafePage(current) }

        if (highlights.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                for (r in highlights) {
                    val isCurrent = r == currentHighlight
                    drawRect(
                        color = if (isCurrent) Color(0xAAFF9800) else Color(0x66FFEB3B),
                        topLeft = Offset(r.left * scale, ch - r.top * scale),
                        size = Size((r.right - r.left) * scale, (r.top - r.bottom) * scale),
                    )
                }
            }
        }

        if (!editMode) {
            NonEditOverlay(
                page = current,
                links = links,
                selectText = selectText,
                cw = cw,
                ch = ch,
                scale = scale,
                onLinkPage = onLinkPage,
            )
        }

        if (editMode) {
            EditOverlay(
                page = current,
                annotations = annotations,
                selected = selected,
                tool = tool,
                shape = shape,
                markup = markup,
                color = color,
                strokeWidth = strokeWidth,
                cw = cw,
                ch = ch,
                scale = scale,
                toPage = ::toPage,
                document = document,
                index = index,
                scope = scope,
                onSelect = onSelect,
                onEdited = onEdited,
                onCreated = onCreated,
                onMoved = onMoved,
                onStartText = onStartText,
                onRequestImage = onRequestImage,
                onRequestNote = onRequestNote,
                onRequestCallout = onRequestCallout,
                polyDraft = polyDraft,
                onAddPolyPoint = onAddPolyPoint,
            )
            FormFieldOverlay(
                fields = formFields,
                ch = ch,
                scale = scale,
                document = document,
                index = index,
                scope = scope,
                onEdited = onFormEdited,
            )

            // Inline text editing: a live text field drawn on the page.
            if (textSession != null) {
                val density = LocalDensity.current
                val focus = remember(textSession.annotId, textSession.origin) { FocusRequester() }
                LaunchedEffect(textSession.annotId, textSession.origin) { focus.requestFocus() }
                val leftDp = with(density) { (textSession.origin.x * scale).toDp() }
                val topDp = with(density) { (ch - textSession.origin.y * scale).toDp() }
                BasicTextField(
                    value = textSession.value,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .offset(x = leftDp, y = topDp)
                        .widthIn(min = 48.dp)
                        .focusRequester(focus)
                        .onFocusChanged { if (!it.isFocused) onCommitText() }
                        .background(Color(0x33448AFF)),
                    textStyle = TextStyle(
                        color = Color(textSession.color),
                        fontSize = with(density) { (textSession.size * scale).toSp() },
                    ),
                    cursorBrush = SolidColor(Color(textSession.color)),
                )
            }
        }
    }
}

@Composable
private fun NonEditOverlay(
    page: SafePdfPage,
    links: List<SafeLink>,
    selectText: Boolean,
    cw: Float,
    ch: Float,
    scale: Float,
    onLinkPage: (Int) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Clickable link boxes.
    for (link in links) {
        val leftDp = with(density) { (link.x0 * scale).toDp() }
        val topDp = with(density) { (ch - link.y1 * scale).toDp() }
        val wDp = with(density) { ((link.x1 - link.x0) * scale).toDp() }
        val hDp = with(density) { ((link.y1 - link.y0) * scale).toDp() }
        Box(
            Modifier
                .padding(start = leftDp, top = topDp)
                .size(wDp, hDp)
                .clickable {
                    if (link.uri.isNotEmpty()) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, link.uri.toUri())
                            )
                        }
                    } else if (link.destPage >= 0) {
                        onLinkPage(link.destPage)
                    }
                },
        )
    }

    // Glyph-level text selection with draggable handles.
    if (selectText) {
        TextSelectionLayer(page = page, ch = ch, scale = scale)
    }
}

/** A single selectable glyph in reading order, with its on-screen rect. */
private data class SelGlyph(val ch: Char, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Glyph-level selection over a page's text primitives: drag to select a range in
 * reading order, adjust with the two handles, and copy the exact substring.
 * Per-glyph x positions are approximated from the run width (no font metrics).
 */
@Composable
private fun TextSelectionLayer(page: SafePdfPage, ch: Float, scale: Float) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Build ordered glyphs once per page/scale.
    val glyphs = remember(page, scale, ch) {
        val list = ArrayList<Triple<Float, Float, SelGlyph>>() // (orderY, orderX, glyph)
        for (prim in page.primitives) {
            if (prim !is PdfPrimitive.Text || prim.text.isEmpty()) continue
            val cw = prim.size * 0.5f
            prim.text.forEachIndexed { i, c ->
                val px = prim.origin.x + i * cw
                val left = px * scale
                val right = (px + cw) * scale
                val baseline = ch - prim.origin.y * scale
                val top = baseline - prim.size * scale
                list.add(Triple(-prim.origin.y, px, SelGlyph(c, left, top, right, baseline)))
            }
        }
        list.sortWith(compareBy({ it.first }, { it.second }))
        list.map { it.third }
    }
    if (glyphs.isEmpty()) return

    var range by remember(page) { mutableStateOf<IntRange?>(null) }
    var dragEnd by remember(page) { mutableStateOf(true) } // which handle is moving

    fun nearest(p: Offset): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        glyphs.forEachIndexed { i, g ->
            val cx = (g.left + g.right) / 2f
            val cy = (g.top + g.bottom) / 2f
            val d = (cx - p.x) * (cx - p.x) + (cy - p.y) * (cy - p.y)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    Canvas(
        Modifier.fillMaxSize().pointerInput(glyphs) {
            detectDragGestures(
                onDragStart = { pos ->
                    val r = range
                    // Grab an existing handle if the touch is near one, else start fresh.
                    if (r != null) {
                        val startG = glyphs[r.first]
                        val endG = glyphs[r.last]
                        val dStart = kotlin.math.hypot(startG.left - pos.x, startG.bottom - pos.y)
                        val dEnd = kotlin.math.hypot(endG.right - pos.x, endG.bottom - pos.y)
                        if (minOf(dStart, dEnd) < 60f) {
                            dragEnd = dEnd <= dStart
                            return@detectDragGestures
                        }
                    }
                    val i = nearest(pos)
                    range = i..i
                    dragEnd = true
                },
                onDrag = { change, _ ->
                    change.consume()
                    val i = nearest(change.position)
                    val r = range ?: (i..i)
                    range = if (dragEnd) minOf(r.first, i)..maxOf(r.first, i)
                    else minOf(i, r.last)..maxOf(i, r.last)
                },
                onDragEnd = {
                    val r = range
                    if (r != null) {
                        val text = glyphs.subList(r.first, r.last + 1).joinToString("") { it.ch.toString() }
                        if (text.isNotBlank()) {
                            scope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("text", text))) }
                            android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        },
    ) {
        val r = range ?: return@Canvas
        for (i in r) {
            val g = glyphs[i]
            drawRect(
                color = Color(0x553F51B5),
                topLeft = Offset(g.left, g.top),
                size = Size(g.right - g.left, g.bottom - g.top),
            )
        }
        // Handles at the two ends.
        val s = glyphs[r.first]
        val e = glyphs[r.last]
        drawCircle(Color(0xFF3F51B5), radius = 14f, center = Offset(s.left, s.bottom))
        drawCircle(Color(0xFF3F51B5), radius = 14f, center = Offset(e.right, e.bottom))
    }
}

@Composable
private fun EditOverlay(
    page: SafePdfPage,
    annotations: List<SafeAnnotation>,
    selected: Long?,
    tool: EditTool,
    shape: ShapeKind,
    markup: MarkupKind,
    color: Color,
    strokeWidth: Float,
    cw: Float,
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

    fun annotAt(screen: Offset): SafeAnnotation? {
        val p = toPage(screen)
        return annotations.lastOrNull { p.x in it.x0..it.x1 && p.y in it.y0..it.y1 }
    }

    val gestures = Modifier.pointerInput(tool, selected, annotations) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            // With Select, only capture the gesture when it starts on an annotation
            // (a move). On empty space we leave it unconsumed so the list scrolls.
            val selectMove = tool == EditTool.SELECT && annotAt(start) != null
            val blockScroll =
                tool == EditTool.HIGHLIGHT || tool == EditTool.MARKUP || tool == EditTool.SHAPE ||
                    tool == EditTool.LINE || tool == EditTool.CALLOUT || tool == EditTool.REDACT ||
                    tool == EditTool.DRAW || selectMove
            if (blockScroll) {
                down.consume()
                dragStart = start
                dragCurrent = start
                if (tool == EditTool.DRAW) inkPoints = listOf(start)
            }
            if (tool == EditTool.SELECT) moveDelta = Offset.Zero

            var dragging = false
            var lastPos = start
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val pos = change.position
                if (!dragging && (pos - start).getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                    if (selectMove) onSelect(annotAt(start)?.id)
                }
                if (dragging) {
                    // Don't consume Select drags on empty space, so they scroll.
                    val consume = tool != EditTool.SELECT || selectMove
                    if (consume) change.consume()
                    when (tool) {
                        EditTool.HIGHLIGHT, EditTool.MARKUP, EditTool.SHAPE, EditTool.LINE, EditTool.CALLOUT, EditTool.REDACT -> dragCurrent = pos
                        EditTool.DRAW -> { dragCurrent = pos; inkPoints = inkPoints + pos }
                        EditTool.SELECT -> if (selectMove) moveDelta += (pos - lastPos)
                        else -> {}
                    }
                }
                lastPos = pos
            }

            if (!dragging) {
                when (tool) {
                    EditTool.TEXT -> {
                        val p = toPage(start)
                        onStartText(
                            TextSession(
                                page = index,
                                origin = p,
                                size = 14f,
                                color = color.toArgb(),
                                annotId = null,
                                value = TextFieldValue("Text", TextRange(0, 4)),
                            )
                        )
                    }
                    EditTool.IMAGE -> onRequestImage(toPage(start))
                    EditTool.NOTE -> onRequestNote(toPage(start))
                    EditTool.POLYLINE, EditTool.BEZIER -> onAddPolyPoint(toPage(start))
                    EditTool.SELECT -> {
                        val hit = annotAt(start)
                        if (hit != null && hit.id == selected && hit.subtype == 1) {
                            val sz = ((hit.y1 - hit.y0) / 1.3f).coerceIn(6f, 72f)
                            onStartText(
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
                            onSelect(hit?.id)
                        }
                    }
                    else -> {}
                }
            } else {
                val s = dragStart
                val e = dragCurrent
                when (tool) {
                    EditTool.HIGHLIGHT -> if (s != null && e != null) {
                        val a = toPage(s); val b = toPage(e)
                        scope.launch {
                            val id = document.addHighlight(index, a.x, a.y, b.x, b.y, color.toArgb())
                            onCreated(id); onEdited()
                        }
                    }
                    EditTool.MARKUP -> if (s != null && e != null) {
                        val a = toPage(s); val b = toPage(e)
                        scope.launch {
                            val id = if (markup == MarkupKind.HIGHLIGHT) {
                                document.addHighlight(index, a.x, a.y, b.x, b.y, color.toArgb())
                            } else {
                                val kind = when (markup) {
                                    MarkupKind.STRIKEOUT -> 1
                                    MarkupKind.SQUIGGLY -> 2
                                    else -> 0
                                }
                                document.addTextMarkup(index, a.x, a.y, b.x, b.y, color.toArgb(), kind)
                            }
                            onCreated(id); onEdited()
                        }
                    }
                    EditTool.CALLOUT -> if (s != null && e != null) {
                        onRequestCallout(toPage(s), toPage(e))
                    }
                    EditTool.SHAPE -> if (s != null && e != null) {
                        val rect = Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
                        val lineWidth = if (shape.isFill) 0f else strokeWidth
                        when (shape.geom) {
                            ShapeGeom.RECT -> {
                                val a = toPage(Offset(rect.left, rect.top)); val b = toPage(Offset(rect.right, rect.bottom))
                                scope.launch {
                                    val id = document.addRect(index, a.x, a.y, b.x, b.y, color.toArgb(), lineWidth, shape.isFill)
                                    onCreated(id); onEdited()
                                }
                            }
                            ShapeGeom.OVAL -> {
                                val a = toPage(Offset(rect.left, rect.top)); val b = toPage(Offset(rect.right, rect.bottom))
                                scope.launch {
                                    val id = document.addOval(index, a.x, a.y, b.x, b.y, color.toArgb(), lineWidth, shape.isFill)
                                    onCreated(id); onEdited()
                                }
                            }
                            ShapeGeom.POLYGON -> {
                                val unit = shape.unitPolygon()
                                val flat = FloatArray(unit.size * 2)
                                unit.forEachIndexed { i, u ->
                                    val pp = toPage(mapUnit(u, rect)); flat[i * 2] = pp.x; flat[i * 2 + 1] = pp.y
                                }
                                scope.launch {
                                    val id = document.addPoly(index, flat, color.toArgb(), lineWidth, shape.isFill, closed = true)
                                    onCreated(id); onEdited()
                                }
                            }
                        }
                    }
                    EditTool.LINE -> if (s != null && e != null) {
                        val a = toPage(s); val b = toPage(e)
                        scope.launch {
                            val id = document.addPoly(index, floatArrayOf(a.x, a.y, b.x, b.y), color.toArgb(), strokeWidth, fill = false, closed = false)
                            onCreated(id); onEdited()
                        }
                    }
                    EditTool.REDACT -> if (s != null && e != null) {
                        val a = toPage(Offset(minOf(s.x, e.x), minOf(s.y, e.y)))
                        val b = toPage(Offset(maxOf(s.x, e.x), maxOf(s.y, e.y)))
                        scope.launch {
                            // Marked redaction box; "Apply redactions" removes the content beneath.
                            val id = document.addRedaction(index, a.x, a.y, b.x, b.y)
                            onCreated(id); onEdited()
                        }
                    }
                    EditTool.DRAW -> {
                        val pts = inkPoints
                        if (pts.size >= 2) {
                            val flat = FloatArray(pts.size * 2)
                            pts.forEachIndexed { i, o ->
                                val pp = toPage(o); flat[i * 2] = pp.x; flat[i * 2 + 1] = pp.y
                            }
                            scope.launch {
                                val id = document.addInk(index, color.toArgb(), strokeWidth, flat)
                                onCreated(id); onEdited()
                            }
                        }
                    }
                    EditTool.SELECT -> if (selectMove) {
                        val id = selected
                        val a = annotations.firstOrNull { it.id == id }
                        if (id != null && a != null) {
                            val dx = moveDelta.x / scale
                            val dy = -moveDelta.y / scale
                            if (dx != 0f || dy != 0f) {
                                val oldR = listOf(a.x0, a.y0, a.x1, a.y1)
                                val newR = listOf(a.x0 + dx, a.y0 + dy, a.x1 + dx, a.y1 + dy)
                                scope.launch {
                                    document.moveAnnotation(index, id, newR[0], newR[1], newR[2], newR[3])
                                    onMoved(id, oldR, newR)
                                    onEdited()
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
            dragStart = null
            dragCurrent = null
            if (tool == EditTool.DRAW) inkPoints = emptyList()
            moveDelta = Offset.Zero
        }
    }

    Canvas(Modifier.fillMaxSize().then(gestures)) {
        // Selection highlight.
        val sel = annotations.firstOrNull { it.id == selected }
        if (sel != null) {
            val left = sel.x0 * scale + moveDelta.x
            val top = ch - sel.y1 * scale + moveDelta.y
            drawRect(
                color = Color(0xFF2196F3),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size((sel.x1 - sel.x0) * scale, (sel.y1 - sel.y0) * scale),
                style = Stroke(width = 3f),
            )
        }
        // In-progress shapes.
        val s = dragStart
        val e = dragCurrent
        if (s != null && e != null && tool == EditTool.HIGHLIGHT) {
            drawRect(
                color = color.copy(alpha = 0.35f),
                topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)),
                size = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)),
                style = Fill,
            )
        }
        if (s != null && e != null && tool == EditTool.MARKUP) {
            val left = minOf(s.x, e.x); val right = maxOf(s.x, e.x)
            val top = minOf(s.y, e.y); val bottom = maxOf(s.y, e.y)
            when (markup) {
                MarkupKind.HIGHLIGHT -> drawRect(
                    color = color.copy(alpha = 0.35f),
                    topLeft = Offset(left, top), size = Size(right - left, bottom - top), style = Fill,
                )
                MarkupKind.STRIKEOUT -> drawLine(color, Offset(left, (top + bottom) / 2f), Offset(right, (top + bottom) / 2f), strokeWidth = 2f)
                else -> drawLine(color, Offset(left, bottom - 2f), Offset(right, bottom - 2f), strokeWidth = 2f)
            }
        }
        if (s != null && e != null && tool == EditTool.CALLOUT) {
            drawLine(color, s, e, strokeWidth = 2f)
            drawRect(color = color, topLeft = e, size = Size(120f, 40f), style = Stroke(width = 2f))
        }
        if (s != null && e != null && tool == EditTool.REDACT) {
            drawRect(
                color = Color.Black,
                topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)),
                size = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)),
                style = Fill,
            )
        }
        if (s != null && e != null && tool == EditTool.SHAPE) {
            val rect = Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
            val topLeft = Offset(rect.left, rect.top)
            val sz = Size(rect.width, rect.height)
            val style = if (shape.isFill) Fill else Stroke(width = 2f)
            when (shape.geom) {
                ShapeGeom.RECT -> drawRect(color = color, topLeft = topLeft, size = sz, style = style)
                ShapeGeom.OVAL -> drawOval(color = color, topLeft = topLeft, size = sz, style = style)
                ShapeGeom.POLYGON -> {
                    val pts = shape.unitPolygon().map { mapUnit(it, rect) }
                    if (pts.size >= 2) {
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        drawPath(path, color, style = style)
                    }
                }
            }
        }
        if (s != null && e != null && tool == EditTool.LINE) {
            drawLine(color, s, e, strokeWidth = 2f)
        }
        // In-progress polyline / Bézier: draw placed points and connecting path.
        if (polyDraft != null && polyDraft.points.isNotEmpty()) {
            val screenPts = polyDraft.points.map { Offset(it.x * scale, ch - it.y * scale) }
            val path = Path().apply {
                moveTo(screenPts[0].x, screenPts[0].y)
                screenPts.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color, style = Stroke(width = 2f))
            for (p in screenPts) {
                drawCircle(color = color, radius = 5f, center = p)
            }
        }
        if (tool == EditTool.DRAW && inkPoints.size >= 2) {
            val path = Path().apply {
                moveTo(inkPoints[0].x, inkPoints[0].y)
                inkPoints.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color, style = Stroke(width = 2f))
        }
    }
}

@Composable
private fun FormFieldOverlay(
    fields: List<SafeFormField>,
    ch: Float,
    scale: Float,
    document: SafePdfDocument,
    index: Int,
    scope: CoroutineScope,
    onEdited: () -> Unit,
) {
    val density = LocalDensity.current
    for (field in fields) {
        val leftDp = with(density) { (field.x0 * scale).toDp() }
        val topDp = with(density) { (ch - field.y1 * scale).toDp() }
        val wDp = with(density) { ((field.x1 - field.x0) * scale).toDp() }
        val hDp = with(density) { ((field.y1 - field.y0) * scale).toDp() }

        when (field.type) {
            0 -> { // text field
                var text by remember(field.id, field.value) { mutableStateOf(field.value) }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .padding(start = leftDp, top = topDp)
                        .size(wDp, hDp)
                        .background(Color(0x332196F3)),
                    singleLine = true,
                )
                DisposableEffect(field.id) {
                    onDispose {
                        if (text != field.value) {
                            scope.launch { document.setTextField(index, field.id, text); onEdited() }
                        }
                    }
                }
            }
            1 -> { // checkbox / button
                var checked by remember(field.id, field.checked) { mutableStateOf(field.checked) }
                Box(Modifier.padding(start = leftDp, top = topDp).size(wDp, hDp)) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            checked = it
                            scope.launch { document.setCheckbox(index, field.id, it); onEdited() }
                        },
                    )
                }
            }
            2 -> { // choice / dropdown (editable combo): edit the value inline
                var text by remember(field.id, field.value) { mutableStateOf(field.value) }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .padding(start = leftDp, top = topDp)
                        .size(wDp, hDp)
                        .background(Color(0x3300B0FF)),
                    singleLine = true,
                )
                DisposableEffect(field.id) {
                    onDispose {
                        if (text != field.value) {
                            scope.launch { document.setTextField(index, field.id, text); onEdited() }
                        }
                    }
                }
            }
            3 -> { // signature / other: show a tappable placeholder
                Box(
                    Modifier
                        .padding(start = leftDp, top = topDp)
                        .size(wDp, hDp)
                        .background(Color(0x22000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Sign",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditToolbar(
    tool: EditTool,
    onTool: (EditTool) -> Unit,
    shape: ShapeKind,
    onShape: (ShapeKind) -> Unit,
    markup: MarkupKind,
    onMarkup: (MarkupKind) -> Unit,
    color: Color,
    onColor: (Color) -> Unit,
    onStyle: () -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    BottomAppBar {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            toolButton(R.drawable.ic_tool_select, "Select", tool == EditTool.SELECT) { onTool(EditTool.SELECT) }
            toolButton(R.drawable.ic_tool_text, "Text", tool == EditTool.TEXT) { onTool(EditTool.TEXT) }
            markupMenuButton(markup = markup, active = tool == EditTool.MARKUP, onMarkup = onMarkup)
            toolButton(R.drawable.ic_tool_draw, "Draw", tool == EditTool.DRAW) { onTool(EditTool.DRAW) }
            shapeMenuButton(shape = shape, active = tool == EditTool.SHAPE, onShape = onShape)
            linesMenuButton(tool = tool, onTool = onTool)
            toolButton(R.drawable.ic_note, "Note", tool == EditTool.NOTE) { onTool(EditTool.NOTE) }
            toolButton(R.drawable.ic_callout, "Callout", tool == EditTool.CALLOUT) { onTool(EditTool.CALLOUT) }
            toolButton(R.drawable.ic_redact, "Redact", tool == EditTool.REDACT) { onTool(EditTool.REDACT) }
            toolButton(R.drawable.ic_tool_image, "Image", tool == EditTool.IMAGE) { onTool(EditTool.IMAGE) }

            for (c in listOf(Color.Red, Color.Yellow, Color.Blue, Color.Black)) {
                Box(
                    Modifier
                        .padding(3.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(c)
                        .pointerInput(c) { detectTapGestures { onColor(c) } },
                    contentAlignment = Alignment.Center,
                ) {
                    if (c.copy(alpha = 1f) == color.copy(alpha = 1f)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }

            IconButton(onStyle) {
                Icon(painterResource(R.drawable.ic_style), contentDescription = "Style")
            }
            if (canDelete) {
                IconButton(onDuplicate) {
                    Icon(painterResource(R.drawable.ic_duplicate), contentDescription = "Duplicate")
                }
                IconButton(onDelete) { IconDelete() }
            }
        }
    }
}

/** Dropdown for text-markup tools (highlight, underline, strikeout, squiggly). */
@Composable
private fun markupMenuButton(
    markup: MarkupKind,
    active: Boolean,
    onMarkup: (MarkupKind) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }) {
            Icon(
                painterResource(markup.icon()),
                contentDescription = "Text markup",
                tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (kind in MarkupKind.entries) {
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            painterResource(kind.icon()),
                            contentDescription = null,
                            tint = if (active && kind == markup) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = { Text(kind.label()) },
                    onClick = { expanded = false; onMarkup(kind) },
                )
            }
        }
    }
}

/** A single toolbar button whose icon reflects the selected [shape]; tapping it
 * opens a dropdown to pick a rectangle/ellipse (outline or filled). */
@Composable
private fun shapeMenuButton(
    shape: ShapeKind,
    active: Boolean,
    onShape: (ShapeKind) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }) {
            Icon(
                painterResource(shape.icon()),
                contentDescription = "Shapes",
                tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (kind in ShapeKind.entries) {
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            painterResource(kind.icon()),
                            contentDescription = null,
                            tint = if (active && kind == shape) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = { Text(kind.label()) },
                    onClick = { expanded = false; onShape(kind) },
                )
            }
        }
    }
}

/** Dropdown for the line tools (straight line, polyline, Bézier). The button
 * icon reflects the active line tool. */
@Composable
private fun linesMenuButton(
    tool: EditTool,
    onTool: (EditTool) -> Unit,
) {
    val lineTools = listOf(
        Triple(EditTool.LINE, R.drawable.ic_tool_line, "Line"),
        Triple(EditTool.POLYLINE, R.drawable.ic_tool_polyline, "Polyline"),
        Triple(EditTool.BEZIER, R.drawable.ic_tool_bezier, "Bézier curve"),
    )
    val active = tool == EditTool.LINE || tool == EditTool.POLYLINE || tool == EditTool.BEZIER
    val currentIcon = lineTools.firstOrNull { it.first == tool }?.second ?: R.drawable.ic_tool_line
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }) {
            Icon(
                painterResource(currentIcon),
                contentDescription = "Lines",
                tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((t, icon, label) in lineTools) {
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            painterResource(icon),
                            contentDescription = null,
                            tint = if (tool == t) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = { Text(label) },
                    onClick = { expanded = false; onTool(t) },
                )
            }
        }
    }
}

@Composable
private fun toolButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick) {
        Icon(
            painterResource(icon),
            contentDescription = contentDescription,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Style picker: color palette + custom RGB, opacity, and line-width sliders.
 * Affects newly drawn annotations. */
@Composable
private fun StyleDialog(
    color: Color,
    onColor: (Color) -> Unit,
    opacity: Float,
    onOpacity: (Float) -> Unit,
    strokeWidth: Float,
    onWidth: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = listOf(
        Color.Red, Color(0xFFFF9800), Color.Yellow, Color(0xFF4CAF50),
        Color.Cyan, Color.Blue, Color(0xFF3F51B5), Color(0xFF9C27B0),
        Color.Magenta, Color.Black, Color.Gray, Color.White,
    )
    var r by remember(color) { mutableFloatStateOf(color.red) }
    var g by remember(color) { mutableFloatStateOf(color.green) }
    var b by remember(color) { mutableFloatStateOf(color.blue) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
        title = { Text("Style") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    for (c in palette) {
                        Box(
                            Modifier
                                .padding(3.dp).size(28.dp).clip(CircleShape).background(c)
                                .pointerInput(c) {
                                    detectTapGestures {
                                        r = c.red; g = c.green; b = c.blue; onColor(Color(r, g, b))
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (c.copy(alpha = 1f) == color.copy(alpha = 1f)) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFCCCCCC)))
                            }
                        }
                    }
                }
                Text("Red", style = MaterialTheme.typography.labelSmall)
                Slider(value = r, onValueChange = { r = it; onColor(Color(r, g, b)) })
                Text("Green", style = MaterialTheme.typography.labelSmall)
                Slider(value = g, onValueChange = { g = it; onColor(Color(r, g, b)) })
                Text("Blue", style = MaterialTheme.typography.labelSmall)
                Slider(value = b, onValueChange = { b = it; onColor(Color(r, g, b)) })
                Text("Opacity ${(opacity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(value = opacity, onValueChange = onOpacity, valueRange = 0.1f..1f)
                Text("Line width ${strokeWidth.toInt()}", style = MaterialTheme.typography.labelSmall)
                Slider(value = strokeWidth, onValueChange = onWidth, valueRange = 1f..20f)
            }
        },
    )
}

/**
 * Draw a page's primitives, mapping PDF page space (origin bottom-left) to the
 * canvas (origin top-left) with a uniform fit-to-width scale + Y-flip.
 */
internal fun DrawScope.drawSafePage(page: SafePdfPage) {
    val scale = size.width / page.width
    val h = size.height

    fun map(p: Offset) = Offset(p.x * scale, h - p.y * scale)

    val textPaint = android.graphics.Paint().apply { isAntiAlias = true }
    val imagePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    for (prim in page.primitives) {
        when (prim) {
            is PdfPrimitive.FillPath -> {
                val path = prim.points.toPath(::map) ?: continue
                if (prim.evenOdd) path.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                drawPath(path, Color(prim.color), style = Fill)
            }

            is PdfPrimitive.StrokePath -> {
                val path = prim.points.toPath(::map) ?: continue
                val pathEffect = if (prim.dash.size >= 2) {
                    androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        prim.dash.map { it * scale }.toFloatArray(),
                        prim.dashPhase * scale,
                    )
                } else {
                    null
                }
                drawPath(
                    path,
                    Color(prim.color),
                    style = Stroke(
                        width = (prim.width * scale).coerceAtLeast(1f),
                        pathEffect = pathEffect,
                    ),
                )
            }

            is PdfPrimitive.Text -> {
                if (prim.text.isBlank()) continue
                val origin = map(prim.origin)
                textPaint.color = prim.color
                textPaint.textSize = (prim.size * scale).coerceAtLeast(1f)
                drawContext.canvas.nativeCanvas.drawText(prim.text, origin.x, origin.y, textPaint)
            }

            is PdfPrimitive.Image -> {
                val bmp = prim.bitmap ?: continue
                val m = prim.ctm
                fun unitToCanvas(u: Float, v: Float): Offset {
                    val pageX = m[0] * u + m[2] * v + m[4]
                    val pageY = m[1] * u + m[3] * v + m[5]
                    return map(Offset(pageX, pageY))
                }
                val bw = bmp.width.toFloat()
                val bh = bmp.height.toFloat()
                val src = floatArrayOf(0f, 0f, bw, 0f, bw, bh, 0f, bh)
                val c00 = unitToCanvas(0f, 1f)
                val c10 = unitToCanvas(1f, 1f)
                val c11 = unitToCanvas(1f, 0f)
                val c01 = unitToCanvas(0f, 0f)
                val dst = floatArrayOf(c00.x, c00.y, c10.x, c10.y, c11.x, c11.y, c01.x, c01.y)
                val matrix = android.graphics.Matrix()
                matrix.setPolyToPoly(src, 0, dst, 0, 4)
                drawContext.canvas.nativeCanvas.drawBitmap(bmp, matrix, imagePaint)
            }
        }
    }
}

private inline fun List<Offset>.toPath(map: (Offset) -> Offset): Path? {
    if (size < 2) return null
    val path = Path()
    val first = map(this[0])
    path.moveTo(first.x, first.y)
    for (i in 1 until size) {
        val p = map(this[i])
        path.lineTo(p.x, p.y)
    }
    return path
}

private class JpegImage(val bytes: ByteArray, val width: Int, val height: Int)

/** Read [uri] and re-encode it as JPEG for a stamp; null on failure. */
private fun readAsJpeg(context: android.content.Context, uri: Uri): JpegImage? = runCatching {
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it)
    } ?: return null
    val out = ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
    JpegImage(out.toByteArray(), bmp.width, bmp.height)
}.getOrNull()
