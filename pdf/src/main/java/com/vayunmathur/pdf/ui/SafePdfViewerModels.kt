package com.vayunmathur.pdf.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.library.ui.IconFormatUnderlined
import com.vayunmathur.library.ui.IconHighlight
import com.vayunmathur.library.ui.IconShapeArrowFill
import com.vayunmathur.library.ui.IconShapeArrowOutline
import com.vayunmathur.library.ui.IconShapeDiamondFill
import com.vayunmathur.library.ui.IconShapeDiamondOutline
import com.vayunmathur.library.ui.IconShapeHexagonFill
import com.vayunmathur.library.ui.IconShapeHexagonOutline
import com.vayunmathur.library.ui.IconShapeOvalFill
import com.vayunmathur.library.ui.IconShapeOvalOutline
import com.vayunmathur.library.ui.IconShapePentagonFill
import com.vayunmathur.library.ui.IconShapePentagonOutline
import com.vayunmathur.library.ui.IconShapeRectFill
import com.vayunmathur.library.ui.IconShapeRectOutline
import com.vayunmathur.library.ui.IconShapeRoundRectFill
import com.vayunmathur.library.ui.IconShapeRoundRectOutline
import com.vayunmathur.library.ui.IconShapeStarFill
import com.vayunmathur.library.ui.IconShapeStarOutline
import com.vayunmathur.library.ui.IconShapeTriangleFill
import com.vayunmathur.library.ui.IconShapeTriangleOutline
import com.vayunmathur.library.ui.IconSquiggly
import com.vayunmathur.library.ui.IconStrikethrough
import com.vayunmathur.pdf.platform.SafePdfDocument
import java.io.ByteArrayOutputStream

internal sealed interface LoadState {
    data object Loading : LoadState
    /** [notPdf] true when the bytes aren't a PDF at all (e.g. an HTML download page), as
     *  opposed to a genuine PDF the parser can't handle. */
    data class Error(val notPdf: Boolean = false) : LoadState
    data class Loaded(val document: SafePdfDocument) : LoadState
}

/** Editing tools. */
internal enum class EditTool { SELECT, TEXT, HIGHLIGHT, MARKUP, DRAW, SHAPE, LINE, POLYLINE, BEZIER, NOTE, CALLOUT, REDACT, IMAGE }

/** Text-markup variants for the [EditTool.MARKUP] tool. */
internal enum class MarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY }

internal fun MarkupKind.icon(): @Composable (Color) -> Unit = when (this) {
    MarkupKind.HIGHLIGHT -> { t -> IconHighlight(tint = t) }
    MarkupKind.UNDERLINE -> { t -> IconFormatUnderlined(tint = t) }
    MarkupKind.STRIKEOUT -> { t -> IconStrikethrough(tint = t) }
    MarkupKind.SQUIGGLY -> { t -> IconSquiggly(tint = t) }
}

internal fun MarkupKind.label(): String = when (this) {
    MarkupKind.HIGHLIGHT -> "Highlight"
    MarkupKind.UNDERLINE -> "Underline"
    MarkupKind.STRIKEOUT -> "Strikeout"
    MarkupKind.SQUIGGLY -> "Squiggly"
}

/** Closed-shape variants for the [EditTool.SHAPE] tool (dragged bounding box). */
internal enum class ShapeKind {
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

internal enum class ShapeGeom { RECT, OVAL, POLYGON }

internal val ShapeKind.isFill: Boolean get() = name.endsWith("_FILL")

internal val ShapeKind.geom: ShapeGeom
    get() = when (this) {
        ShapeKind.RECT_OUTLINE, ShapeKind.RECT_FILL -> ShapeGeom.RECT
        ShapeKind.OVAL_OUTLINE, ShapeKind.OVAL_FILL -> ShapeGeom.OVAL
        else -> ShapeGeom.POLYGON
    }

/**
 * Vertices for [ShapeGeom.POLYGON] shapes in the unit square (x,y in 0..1,
 * y-down), to be scaled into the dragged bounding box. Empty for rect/oval.
 */
internal fun ShapeKind.unitPolygon(): List<Offset> = when (this) {
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
internal fun regularPolygonUnit(n: Int): List<Offset> = (0 until n).map { k ->
    val a = -Math.PI / 2 + 2 * Math.PI * k / n
    Offset(0.5f + 0.5f * kotlin.math.cos(a).toFloat(), 0.5f + 0.5f * kotlin.math.sin(a).toFloat())
}

/** [points]-pointed star with [outer]/[inner] radii, first point at top, y-down. */
internal fun starUnit(points: Int, outer: Float, inner: Float): List<Offset> =
    (0 until points * 2).map { k ->
        val r = if (k % 2 == 0) outer else inner
        val a = -Math.PI / 2 + Math.PI * k / points
        Offset(0.5f + r * kotlin.math.cos(a).toFloat(), 0.5f + r * kotlin.math.sin(a).toFloat())
    }

/** Rounded rectangle perimeter as a polygon, corner [radius] in unit space with
 * [seg] segments per corner. */
internal fun roundRectUnit(radius: Float, seg: Int): List<Offset> {
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
internal fun mapUnit(u: Offset, rect: Rect): Offset =
    Offset(rect.left + u.x * rect.width, rect.top + u.y * rect.height)

/**
 * Smooth an open sequence of [points] into a flattened curve passing through
 * them (Catmull-Rom → cubic Bézier, sampled), for the Bézier tool.
 */
internal fun flattenSmooth(points: List<Offset>): List<Offset> {
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

internal fun ShapeKind.icon(): @Composable (Color) -> Unit = when (this) {
    ShapeKind.RECT_OUTLINE -> { t -> IconShapeRectOutline(tint = t) }
    ShapeKind.RECT_FILL -> { t -> IconShapeRectFill(tint = t) }
    ShapeKind.ROUNDRECT_OUTLINE -> { t -> IconShapeRoundRectOutline(tint = t) }
    ShapeKind.ROUNDRECT_FILL -> { t -> IconShapeRoundRectFill(tint = t) }
    ShapeKind.OVAL_OUTLINE -> { t -> IconShapeOvalOutline(tint = t) }
    ShapeKind.OVAL_FILL -> { t -> IconShapeOvalFill(tint = t) }
    ShapeKind.TRIANGLE_OUTLINE -> { t -> IconShapeTriangleOutline(tint = t) }
    ShapeKind.TRIANGLE_FILL -> { t -> IconShapeTriangleFill(tint = t) }
    ShapeKind.DIAMOND_OUTLINE -> { t -> IconShapeDiamondOutline(tint = t) }
    ShapeKind.DIAMOND_FILL -> { t -> IconShapeDiamondFill(tint = t) }
    ShapeKind.PENTAGON_OUTLINE -> { t -> IconShapePentagonOutline(tint = t) }
    ShapeKind.PENTAGON_FILL -> { t -> IconShapePentagonFill(tint = t) }
    ShapeKind.HEXAGON_OUTLINE -> { t -> IconShapeHexagonOutline(tint = t) }
    ShapeKind.HEXAGON_FILL -> { t -> IconShapeHexagonFill(tint = t) }
    ShapeKind.STAR_OUTLINE -> { t -> IconShapeStarOutline(tint = t) }
    ShapeKind.STAR_FILL -> { t -> IconShapeStarFill(tint = t) }
    ShapeKind.ARROW_OUTLINE -> { t -> IconShapeArrowOutline(tint = t) }
    ShapeKind.ARROW_FILL -> { t -> IconShapeArrowFill(tint = t) }
}

internal fun ShapeKind.label(): String {
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
internal data class PolyDraft(val page: Int, val points: List<Offset>, val bezier: Boolean)

/**
 * A reversible edit. ADDED (undo detaches), REMOVED (undo re-attaches), or MOVED
 * (undo restores [oldRect], redo restores [newRect]). Rects are page-space
 * [x0,y0,x1,y1].
 */
internal enum class EditKind { ADDED, REMOVED, MOVED }

internal data class EditAction(
    val page: Int,
    val annotId: Long,
    val kind: EditKind,
    val oldRect: List<Float>? = null,
    val newRect: List<Float>? = null,
)

/**
 * Pinch-zoom bounds. 1 is "page fills the viewport width"; below that the page is
 * drawn narrower than the screen so more of the document fits on it at once.
 */
internal const val MIN_ZOOM = 0.5f
/// Minimum max-zoom (applies to normal-sized pages). Large pages raise this via
/// [maxZoomFor] so their fine detail can actually be inspected (issue #321
/// doesntzoomfully/doesntzoomfully2 were capped too low to read large pages).
internal const val MAX_ZOOM = 6f
/// Absolute ceiling to bound pan math / memory regardless of page size.
internal const val ABS_MAX_ZOOM = 40f
/// A large page may be zoomed until ~this many device pixels map to one PDF
/// point, past the fit-to-width baseline.
internal const val TARGET_PX_PER_POINT = 4f
internal const val DOUBLE_TAP_ZOOM = 2.5f
internal const val DOUBLE_TAP_ZOOM_THRESHOLD = 1.2f

/// Effective maximum zoom for a page [pageWidthPts] points wide shown in a
/// [viewportWidthPx]-wide viewport. At zoom 1 the page fills the viewport width,
/// so `Z` device-px per point = `Z * viewportWidthPx / pageWidthPts`; solving for
/// [TARGET_PX_PER_POINT] gives the cap. Clamped to [MAX_ZOOM]..[ABS_MAX_ZOOM] so
/// small pages keep a sane limit and huge pages can't overflow the pan math.
internal fun maxZoomFor(pageWidthPts: Float, viewportWidthPx: Int): Float {
    if (pageWidthPts <= 0f || viewportWidthPx <= 0) return MAX_ZOOM
    val z = TARGET_PX_PER_POINT * pageWidthPts / viewportWidthPx
    return z.coerceIn(MAX_ZOOM, ABS_MAX_ZOOM)
}

/**
 * Maximum horizontal zoom [pan] (screen-pixel translation) for a [viewportWidthPx]
 * viewport at [zoom]. The layer pivots on its top-centre, so the range is
 * symmetric about zero. Vertical has no pan: [SafePdfScrollState] owns it.
 */
internal fun maxPanX(viewportWidthPx: Int, zoom: Float): Float =
    (viewportWidthPx * (zoom - 1f) / 2f).coerceAtLeast(0f)

/**
 * An in-progress inline text edit. [origin] is the top of the text box in page
 * space; [annotId] is set when editing an existing FreeText, null for a new one.
 */
internal data class TextSession(
    val page: Int,
    val origin: Offset,
    val size: Float,
    val color: Int,
    val annotId: Long?,
    val value: TextFieldValue,
)
internal class JpegImage(val bytes: ByteArray, val width: Int, val height: Int)

/** Read [uri] and re-encode it as JPEG for a stamp; null on failure. */
internal fun readAsJpeg(context: android.content.Context, uri: Uri): JpegImage? = runCatching {
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it)
    } ?: return null
    val out = ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
    JpegImage(out.toByteArray(), bmp.width, bmp.height)
}.getOrNull()
