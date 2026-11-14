package com.vayunmathur.library.ui.odf

/** One presentation slide: elements, background, notes and transition. */
data class OdfSlide(
    val name: String,
    val elements: List<OdfSlideElement> = emptyList(),
    val backgroundColor: Long? = null,
    val backgroundImagePath: String? = null,
    val notes: List<OdfParagraph> = emptyList(),
    // Slide transition (presentation:transition-* on the drawing-page style). (Round 2 R9)
    val transitionType: String? = null,   // e.g. "fade", "wipe", "dissolve"
    val transitionSpeed: String? = null,  // "slow" | "medium" | "fast"
    // Linked master page name (draw:master-page-name), preserved for round-trip. (Round 3)
    val masterName: String? = null
)

/** One element placed on a slide: a frame or a shape. */
sealed class OdfSlideElement {
    /** A placed frame holding paragraphs, an image or a chart. */
    data class Frame(val frame: OdfFrame) : OdfSlideElement()
    /** A placed vector shape. */
    data class Shape(val shape: OdfShape) : OdfSlideElement()
}

/** Geometry (x, y, width, height) of a floating element, in px@96. (Phase 1) */
fun OdfSlideElement.bounds(): FloatArray = when (this) {
    is OdfSlideElement.Frame -> floatArrayOf(frame.x, frame.y, frame.width, frame.height)
    is OdfSlideElement.Shape -> when (val s = shape) {
        // A line's (x,y) is its first endpoint, which may not be the top-left; position the
        // bounding box at the min corner so it isn't drawn off to one side.
        is OdfShape.Line -> floatArrayOf(
            minOf(s.x, s.x2), minOf(s.y, s.y2),
            kotlin.math.abs(s.x2 - s.x), kotlin.math.abs(s.y2 - s.y)
        )
        else -> floatArrayOf(s.x, s.y, s.width, s.height)
    }
}

/** Returns a copy of the element repositioned/resized to the given bounds (px@96). (Phase 1) */
fun setElementBounds(el: OdfSlideElement, x: Float, y: Float, w: Float, h: Float): OdfSlideElement = when (el) {
    is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(x = x, y = y, width = w, height = h))
    is OdfSlideElement.Shape -> OdfSlideElement.Shape(when (val s = el.shape) {
        is OdfShape.Rect -> s.copy(x = x, y = y, width = w, height = h)
        is OdfShape.Ellipse -> s.copy(x = x, y = y, width = w, height = h)
        is OdfShape.Line -> s.copy(x = x, y = y, width = w, height = h)
        is OdfShape.CustomShape -> s.copy(x = x, y = y, width = w, height = h)
        is OdfShape.Polyline -> s.copy(x = x, y = y, width = w, height = h)
    })
}

/** Geometry and content of a frame placed on a slide. */
data class OdfFrame(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val paragraphs: List<OdfParagraph>,
    val image: OdfImage? = null,
    val chart: OdfChart? = null,
    val fillColor: Long? = null,
    val strokeColor: Long? = null,
    val strokeWidth: Float? = null,
    val fillGradient: OdfGradient? = null,
    /** Rotation in degrees clockwise (from the shape/frame transform). */
    val rotationDegrees: Float = 0f
)

/** A vector shape on a slide: geometry, fill, stroke and text. */
sealed class OdfShape {
    abstract val x: Float
    abstract val y: Float
    abstract val width: Float
    abstract val height: Float
    abstract val fillColor: Long?
    abstract val strokeColor: Long?
    abstract val strokeWidth: Float?
    abstract val text: List<OdfParagraph>
    /** Rotation in degrees clockwise (from draw:transform rotate). (Round 3) */
    open val rotationDegrees: Float get() = 0f
    /** Linear/axial gradient fill, if any (overrides solid fillColor for rendering). (Round 3) */
    open val fillGradient: OdfGradient? get() = null
    /** Dashed stroke (draw:stroke="dash"). (Round 3) */
    open val strokeDashed: Boolean get() = false
    /** Arrowhead markers at line/connector ends (draw:marker-start/-end). (Round 3) */
    open val markerStart: Boolean get() = false
    open val markerEnd: Boolean get() = false

    /** A rectangle, optionally rounded. */
    data class Rect(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList(),
        val cornerRadius: Float = 0f,
        override val rotationDegrees: Float = 0f,
        override val fillGradient: OdfGradient? = null,
        override val strokeDashed: Boolean = false
    ) : OdfShape()

    /** An ellipse. */
    data class Ellipse(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList(),
        override val rotationDegrees: Float = 0f,
        override val fillGradient: OdfGradient? = null,
        override val strokeDashed: Boolean = false
    ) : OdfShape()

    /** A straight line between two endpoints, with optional arrow markers. */
    data class Line(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList(),
        val x2: Float = 0f, val y2: Float = 0f,
        override val rotationDegrees: Float = 0f,
        override val strokeDashed: Boolean = false,
        override val markerStart: Boolean = false,
        override val markerEnd: Boolean = false
    ) : OdfShape()

    /** A preset/custom geometry shape defined by the document. */
    data class CustomShape(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList(),
        override val rotationDegrees: Float = 0f,
        override val fillGradient: OdfGradient? = null,
        override val strokeDashed: Boolean = false
    ) : OdfShape()

    /** Polyline (open) or polygon (closed) with absolute px@96 vertices. (Priority 8) */
    data class Polyline(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList(),
        val points: List<Pair<Float, Float>> = emptyList(),
        val closed: Boolean = false,
        override val rotationDegrees: Float = 0f,
        override val fillGradient: OdfGradient? = null,
        override val strokeDashed: Boolean = false
    ) : OdfShape()
}

/** Linear/axial gradient fill (draw:gradient). [angle] is in degrees. (Round 3) */
data class OdfGradient(
    val startColor: Long,
    val endColor: Long,
    val angle: Float = 0f,
    val style: String = "linear"
)
