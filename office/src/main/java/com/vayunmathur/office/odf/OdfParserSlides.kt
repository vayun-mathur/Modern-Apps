package com.vayunmathur.office.odf

import android.util.Base64
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfGradient
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfMetadata
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.ParagraphStyle
import org.xmlpull.v1.XmlPullParser

// --- Presentation / Drawing ---

/** True for a START_TAG named [name] in a namespace containing [ns]. */
private const val FULL_OPACITY = 100f
private const val CURVE_STEPS = 8
private const val ARC_SKIP_COUNT = 5
private const val CUBIC_WEIGHT = 3f

internal fun isTag(
    eventType: Int,
    parser: XmlPullParser,
    name: String,
    ns: String,
): Boolean = eventType == XmlPullParser.START_TAG &&
    parser.name == name &&
    parser.namespace?.contains(ns) == true

internal fun OdfParser.parsePresentation(
    xml: String, styles: Map<String, StyleInfo>,
    title: String, metadata: OdfMetadata, images: Map<String, ByteArray>,
    objectContents: Map<String, String> = emptyMap()
): OdfDocument.Presentation =
    OdfDocument.Presentation(title, parseSlides(xml, styles, images, objectContents), metadata, images)

internal fun OdfParser.parseDrawing(
    xml: String, styles: Map<String, StyleInfo>,
    title: String, metadata: OdfMetadata, images: Map<String, ByteArray>,
    objectContents: Map<String, String> = emptyMap()
): OdfDocument.Drawing =
    OdfDocument.Drawing(title, parseSlides(xml, styles, images, objectContents), metadata, images)

internal fun OdfParser.parseSlides(
    xml: String,
    styles: Map<String,
    StyleInfo>,
    images: Map<String,
    ByteArray>,
    objectContents: Map<String,
    String> = emptyMap()): List<OdfSlide> {
    val slides = mutableListOf<OdfSlide>()
    val parser = newParser(xml)
    var eventType = parser.eventType

    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (isTag(eventType, parser, "page", "draw")) {
            val name = getAttr(parser, "name") ?: "Slide ${slides.size + 1}"
            val drawStyleName = getAttr(parser, "style-name")
            val masterName = getAttr(parser, "master-page-name")
            val resolved = resolveStyle(drawStyleName, styles)
            val result = parseSlideContent(parser, styles, images, objectContents)
            slides.add(OdfSlide(
                name = name, elements = result.elements,
                backgroundColor = resolved.drawFillColor, notes = result.notes,
                transitionType = resolved.transitionType, transitionSpeed = resolved.transitionSpeed,
                masterName = masterName
            ))
        }
        eventType = parser.next()
    }
    return slides
}

internal data class SlideParseResult(val elements: List<OdfSlideElement>, val notes: List<OdfParagraph>)

internal fun OdfParser.parseSlideContent(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    images: Map<String, ByteArray>,
    objectContents: Map<String, String> = emptyMap(),
): SlideParseResult {
    val elements = mutableListOf<OdfSlideElement>()
    val notes = mutableListOf<OdfParagraph>()
    val depth = parser.depth
    var eventType = parser.next()
    val ctx = SlideCtx(styles, images, objectContents)

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) {
            applySlideContentTag(parser, elements, notes, ctx)
        }
        eventType = parser.next()
    }
    return SlideParseResult(elements, notes)
}

/** Slide-content shared context. */
private class SlideCtx(
    val styles: Map<String, StyleInfo>,
    val images: Map<String, ByteArray>,
    val objectContents: Map<String, String>,
)

private fun OdfParser.applySlideContentTag(
    parser: XmlPullParser,
    elements: MutableList<OdfSlideElement>,
    notes: MutableList<OdfParagraph>,
    ctx: SlideCtx,
) {
    when (parser.name) {
        "frame" -> elements.add(
            OdfSlideElement.Frame(
                parseSingleFrame(parser, ctx.styles, ctx.images, ctx.objectContents)
            )
        )
        "rect" -> elements.add(OdfSlideElement.Shape(parseShape(parser, ctx.styles, "rect")))
        "ellipse" -> elements.add(OdfSlideElement.Shape(parseShape(parser, ctx.styles, "ellipse")))
        "line" -> elements.add(OdfSlideElement.Shape(parseShape(parser, ctx.styles, "line")))
        "custom-shape" -> elements.add(
            OdfSlideElement.Shape(parseShape(parser, ctx.styles, "custom-shape"))
        )
        "polyline" -> elements.add(
            OdfSlideElement.Shape(parseShape(parser, ctx.styles, "polyline"))
        )
        "polygon" -> elements.add(
            OdfSlideElement.Shape(parseShape(parser, ctx.styles, "polygon"))
        )
        "path" -> elements.add(OdfSlideElement.Shape(parsePathShape(parser, ctx.styles)))
        "notes" -> readSlideNotes(parser, ctx, notes)
    }
}

/** Slide notes (draw:frame text). */
private fun OdfParser.readSlideNotes(
    parser: XmlPullParser,
    ctx: SlideCtx,
    notes: MutableList<OdfParagraph>,
) {
    val noteDepth = parser.depth
    var noteEvent = parser.next()
    while (!(noteEvent == XmlPullParser.END_TAG && parser.depth == noteDepth)) {
        if (noteEvent == XmlPullParser.START_TAG && parser.name == "frame") {
            val frame = parseSingleFrame(parser, ctx.styles, ctx.images, ctx.objectContents)
            for (para in frame.paragraphs) {
                if (para.spans.isNotEmpty()) notes.add(para)
            }
        }
        noteEvent = parser.next()
    }
}

internal fun OdfParser.parseSingleFrame(
    parser: XmlPullParser,
    styles: Map<String,
    StyleInfo>,
    images: Map<String,
    ByteArray>,
    objectContents: Map<String,
    String> = emptyMap()): OdfFrame {
    val x = parseDimension(getAttr(parser, "x"))
    val y = parseDimension(getAttr(parser, "y"))
    val w = parseDimension(getAttr(parser, "width"))
    val h = parseDimension(getAttr(parser, "height"))
    val rot = parseRotationDegrees(getAttr(parser, "transform"))
    val clip = parseClip(getAttr(parser, "clip"))
    val anchor = getAttr(parser, "anchor-type") ?: ""
    val styleName = getAttr(parser, "style-name")
    val resolved = resolveStyle(styleName, styles)

    val frame = FrameAcc(w = w, h = h, anchor = anchor, rot = rot)
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) {
            applyFrameTag(parser, styles, images, objectContents, frame)
        }
        eventType = parser.next()
    }
    if (frame.image != null) {
        // Prefer the legacy inline percent clip; otherwise resolve absolute lengths from the graphic style. (A7)
        val frac = clip ?: parseClipLengths(resolved.clip, frame.image!!.naturalWidthPx, frame.image!!.naturalHeightPx)
        if (frac != null) frame.image = frame.image!!.copy(
            cropLeftPct = frac[CROP_LEFT],
            cropTopPct = frac[CROP_TOP],
            cropRightPct = frac[CROP_RIGHT],
            cropBottomPct = frac[CROP_BOTTOM])
        if (frame.altTitle != null || frame.altDesc != null) {
            frame.image = frame.image!!.copy(altTitle = frame.altTitle, altDesc = frame.altDesc)
        }
    }
    return OdfFrame(
        x,
        y,
        w,
        h,
        frame.paragraphs,
        frame.image,
        chart = frame.chart,
        fillColor = resolved.drawFillColor,
        strokeColor = resolved.drawStrokeColor,
        strokeWidth = resolved.drawStrokeWidth,
        fillGradient = resolved.fillGradientName?.let { gradientDefs[it] })
}

/** Frame accumulation state. */
private class FrameAcc(
    val paragraphs: MutableList<OdfParagraph> = mutableListOf(),
    var image: OdfImage? = null,
    var chart: OdfChart? = null,
    var altTitle: String? = null,
    var altDesc: String? = null,
    var w: Float = 0f,
    var h: Float = 0f,
    var anchor: String = "",
    var rot: Float = 0f,
)

/** Apply one frame child tag. */
private fun OdfParser.applyFrameTag(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    images: Map<String, ByteArray>,
    objectContents: Map<String, String>,
    frame: FrameAcc,
) {
    when (parser.name) {
        "title", "desc" -> if (parser.namespace?.contains("svg") == true) {
            readSvgTitleDesc(parser, frame)
        }
        "text-box" -> readFrameTextBox(parser, styles, images, frame)
        "object" -> {
            // Embedded chart object referenced by ./Object N. (A8)
            val href = getAttr(parser, "href")?.removePrefix("./")
            val xml = href?.let { objectContents["$it/content.xml"] }
            if (xml != null) parseChart(xml)?.let { frame.chart = it }
        }
        "image" -> readFrameImage(parser, styles, images, frame)
        "p" -> if (parser.namespace?.contains("text") == true) {
            val spans = parseInlineContent(parser, "p", styles, images)
            if (spans.isNotEmpty()) frame.paragraphs.add(OdfParagraph(spans))
        }
    }
}

/** SVG title/desc. */
private fun readSvgTitleDesc(parser: XmlPullParser, frame: FrameAcc) {
    val d = parser.depth; val sb = StringBuilder(); var ev = parser.next()
    while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
        if (ev == XmlPullParser.TEXT) sb.append(parser.text)
        if (ev == XmlPullParser.END_DOCUMENT) break
        ev = parser.next()
    }
    val t = sb.toString().trim().ifEmpty { null }
    if (parser.name == "title") frame.altTitle = t else frame.altDesc = t
}

/** Frame text box (paragraphs + lists). */
private fun OdfParser.readFrameTextBox(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    images: Map<String, ByteArray>,
    frame: FrameAcc,
) {
    val boxDepth = parser.depth
    var boxEvent = parser.next()
    while (!(boxEvent == XmlPullParser.END_TAG && parser.depth == boxDepth)) {
        if (isTag(boxEvent, parser, "p", "text")) {
            val spans = parseInlineContent(parser, "p", styles, images)
            if (spans.isNotEmpty()) frame.paragraphs.add(OdfParagraph(spans))
        } else if (boxEvent == XmlPullParser.START_TAG && parser.name == "list") {
            parseListInFrame(parser, styles, images, frame.paragraphs)
        }
        boxEvent = parser.next()
    }
}

/** Frame image (packaged or base64). */
private fun OdfParser.readFrameImage(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    images: Map<String, ByteArray>,
    frame: FrameAcc,
) {
    val resolved = resolveStyle(getAttr(parser, "style-name"), styles)
    val href = getAttr(parser, "href")
    if (href != null && images.containsKey(href)) {
        val bytes = images[href]!!
        val (nw, nh) = decodeNaturalSize(bytes)
        frame.image = OdfImage(
            path = href,
            imageData = bytes,
            width = frame.w,
            height = frame.h,
            anchorType = frame.anchor,
            rotationDegrees = frame.rot,
            naturalWidthPx = nw,
            naturalHeightPx = nh,
            opacityPercent = resolved.imageOpacity ?: FULL_OPACITY,
            colorMode = resolved.imageColorMode)
    }
    readInlineImageData(parser, frame, resolved)
}

/** Inline base64 image data within a frame image. */
private fun OdfParser.readInlineImageData(
    parser: XmlPullParser,
    frame: FrameAcc,
    resolved: StyleInfo,
) {
    val imgDepth = parser.depth
    var imgEvent = parser.next()
    while (!(imgEvent == XmlPullParser.END_TAG && parser.depth == imgDepth)) {
        if (imgEvent == XmlPullParser.START_TAG && parser.name == "binary-data") {
            imgEvent = parser.next()
            if (imgEvent == XmlPullParser.TEXT) {
                try {
                    val bytes = Base64.decode(parser.text.trim(), Base64.DEFAULT)
                    val (nw, nh) = decodeNaturalSize(bytes)
                    frame.image = OdfImage(
                        path = "inline",
                        imageData = bytes,
                        width = frame.w,
                        height = frame.h,
                        anchorType = frame.anchor,
                        rotationDegrees = frame.rot,
                        naturalWidthPx = nw,
                        naturalHeightPx = nh,
                        opacityPercent = resolved.imageOpacity ?: FULL_OPACITY,
                        colorMode = resolved.imageColorMode)
                } catch (_: Exception) { }
            }
        }
        imgEvent = parser.next()
    }
}

internal fun OdfParser.parseListInFrame(
    parser: XmlPullParser,
    styles: Map<String,
    StyleInfo>,
    images: Map<String,
    ByteArray>,
    paragraphs: MutableList<OdfParagraph>) {
    val depth = parser.depth
    var eventType = parser.next()
    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (isTag(eventType, parser, "p", "text")) {
            val spans = parseInlineContent(parser, "p", styles, images)
            if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans, ParagraphStyle.LIST_ITEM, listLevel = 1))
        } else if (eventType == XmlPullParser.START_TAG && parser.name == "list") {
            parseListInFrame(parser, styles, images, paragraphs)
        }
        eventType = parser.next()
    }
}

// --- Shapes ---

internal fun OdfParser.parseShape(parser: XmlPullParser, styles: Map<String, StyleInfo>, shapeName: String): OdfShape {
    val geom = shapeGeometry(parser, shapeName)
    val styleName = getAttr(parser, "style-name")
    val resolved = resolveStyle(styleName, styles)
    val rot = parseRotationDegrees(getAttr(parser, "transform"))
    val grad = resolved.fillGradientName?.let { gradientDefs[it] }

    val text = readShapeText(parser, styles)

    return buildShape(shapeName, geom, resolved, rot, grad, text)
}

/** Shape geometry (position/size/points). */
private class ShapeGeom(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val polyPoints: List<Pair<Float, Float>> = emptyList(),
    val cornerRadius: Float = 0f,
)

/** Geometry for a shape (lines use x1/y1/x2/y2). */
private fun OdfParser.shapeGeometry(parser: XmlPullParser, shapeName: String): ShapeGeom {
    // draw:line uses svg:x1/y1/x2/y2; all other shapes use svg:x/y/width/height.
    val isLine = shapeName == "line"
    val x = parseDimension(getAttr(parser, if (isLine) "x1" else "x"))
    val y = parseDimension(getAttr(parser, if (isLine) "y1" else "y"))
    val x2 = if (isLine) parseDimension(getAttr(parser, "x2")) else 0f
    val y2 = if (isLine) parseDimension(getAttr(parser, "y2")) else 0f
    val w = if (isLine) kotlin.math.abs(x2 - x) else parseDimension(getAttr(parser, "width"))
    val h = if (isLine) kotlin.math.abs(y2 - y) else parseDimension(getAttr(parser, "height"))
    val polyPoints = if (shapeName == "polyline" || shapeName == "polygon") {
        val vb = getAttr(parser, "viewBox")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() }
        parsePolyPoints(getAttr(parser, "points"), vb, x, y, w, h)
    } else emptyList()
    val cornerRadius = if (shapeName == "rect") parseDimension(getAttr(parser, "corner-radius")) else 0f
    return ShapeGeom(x, y, w, h, x2, y2, polyPoints, cornerRadius)
}

/** Text paragraphs within a shape. */
private fun OdfParser.readShapeText(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
): List<OdfParagraph> {
    val text = mutableListOf<OdfParagraph>()
    val depth = parser.depth
    var eventType = parser.next()
    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (isTag(eventType, parser, "p", "text")) {
            val spans = parseInlineContent(parser, "p", styles)
            if (spans.isNotEmpty()) text.add(OdfParagraph(spans))
        }
        eventType = parser.next()
    }
    return text
}

/** Build the shape model. */
private fun buildShape(
    shapeName: String,
    geom: ShapeGeom,
    resolved: StyleInfo,
    rot: Float,
    grad: OdfGradient?,
    text: List<OdfParagraph>,
): OdfShape {
    val ctx = ShapeCtx(geom, resolved, rot, grad, text)
    return when (shapeName) {
        "rect" -> rectShape(ctx)
        "ellipse" -> ellipseShape(ctx)
        "line" -> lineShape(ctx)
        "polyline" -> polyShape(ctx, closed = false)
        "polygon" -> polyShape(ctx, closed = true)
        else -> customShape(ctx)
    }
}

/** Shape build context. */
private class ShapeCtx(
    val geom: ShapeGeom,
    val resolved: StyleInfo,
    val rot: Float,
    val grad: OdfGradient?,
    val text: List<OdfParagraph>,
)

/** Rectangle shape. */
private fun rectShape(ctx: ShapeCtx): OdfShape.Rect {
    val geom = ctx.geom
    val resolved = ctx.resolved
    return OdfShape.Rect(
        geom.x,
        geom.y,
        geom.w,
        geom.h,
        resolved.drawFillColor,
        resolved.drawStrokeColor,
        resolved.drawStrokeWidth,
        ctx.text,
        cornerRadius = geom.cornerRadius,
        rotationDegrees = ctx.rot,
        fillGradient = ctx.grad,
        strokeDashed = resolved.strokeDashed)
}

/** Ellipse shape. */
private fun ellipseShape(ctx: ShapeCtx): OdfShape.Ellipse {
    val geom = ctx.geom
    val resolved = ctx.resolved
    return OdfShape.Ellipse(
        geom.x,
        geom.y,
        geom.w,
        geom.h,
        resolved.drawFillColor,
        resolved.drawStrokeColor,
        resolved.drawStrokeWidth,
        ctx.text,
        rotationDegrees = ctx.rot,
        fillGradient = ctx.grad,
        strokeDashed = resolved.strokeDashed)
}

/** Line shape. */
private fun lineShape(ctx: ShapeCtx): OdfShape.Line {
    val geom = ctx.geom
    val resolved = ctx.resolved
    return OdfShape.Line(
        geom.x,
        geom.y,
        geom.w,
        geom.h,
        resolved.drawFillColor,
        resolved.drawStrokeColor,
        resolved.drawStrokeWidth,
        ctx.text,
        geom.x2,
        geom.y2,
        rotationDegrees = ctx.rot,
        strokeDashed = resolved.strokeDashed,
        markerStart = resolved.markerStart,
        markerEnd = resolved.markerEnd)
}

/** Polyline/polygon shape. */
private fun polyShape(ctx: ShapeCtx, closed: Boolean): OdfShape.Polyline {
    val geom = ctx.geom
    val resolved = ctx.resolved
    return OdfShape.Polyline(
        geom.x,
        geom.y,
        geom.w,
        geom.h,
        resolved.drawFillColor,
        resolved.drawStrokeColor,
        resolved.drawStrokeWidth,
        ctx.text,
        geom.polyPoints,
        closed = closed,
        rotationDegrees = ctx.rot,
        fillGradient = ctx.grad,
        strokeDashed = resolved.strokeDashed)
}

/** Custom shape. */
private fun customShape(ctx: ShapeCtx): OdfShape.CustomShape {
    val geom = ctx.geom
    val resolved = ctx.resolved
    return OdfShape.CustomShape(
        geom.x,
        geom.y,
        geom.w,
        geom.h,
        resolved.drawFillColor,
        resolved.drawStrokeColor,
        resolved.drawStrokeWidth,
        ctx.text,
        rotationDegrees = ctx.rot,
        fillGradient = ctx.grad,
        strokeDashed = resolved.strokeDashed)
}

/** Parses a draw:path freeform shape, sampling svg:d into absolute vertices. */
internal fun OdfParser.parsePathShape(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfShape {
    val x = parseDimension(getAttr(parser, "x"))
    val y = parseDimension(getAttr(parser, "y"))
    val w = parseDimension(getAttr(parser, "width"))
    val h = parseDimension(getAttr(parser, "height"))
    val vb = getAttr(parser, "viewBox")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() }
    val d = getAttr(parser, "d")
    val styleName = getAttr(parser, "style-name")
    val resolved = resolveStyle(styleName, styles)
    val rot = parseRotationDegrees(getAttr(parser, "transform"))
    val grad = resolved.fillGradientName?.let { gradientDefs[it] }
    val (points, closed) = sampleSvgPath(d, vb, x, y, w, h)

    val text = mutableListOf<OdfParagraph>()
    val depth = parser.depth
    var eventType = parser.next()
    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (isTag(eventType, parser, "p", "text")) {
            val spans = parseInlineContent(parser, "p", styles)
            if (spans.isNotEmpty()) text.add(OdfParagraph(spans))
        }
        eventType = parser.next()
    }
    return OdfShape.Polyline(
        x,
        y,
        w,
        h,
        resolved.drawFillColor,
        resolved.drawStrokeColor,
        resolved.drawStrokeWidth,
        text,
        points,
        closed = closed,
        rotationDegrees = rot,
        fillGradient = grad,
        strokeDashed = resolved.strokeDashed)
}

/** Flattens an SVG path 'd' (viewBox space) into absolute px@96 vertices; returns (points, closed). (Phase 2) */
internal fun OdfParser.sampleSvgPath(
    d: String?,
    vb: List<Float>?,
    x: Float,
    y: Float,
    w: Float,
    h: Float): Pair<List<Pair<Float, Float>>, Boolean> {
    if (d.isNullOrBlank()) return emptyList<Pair<Float, Float>>() to false
    val vbMinX = vb?.getOrNull(0) ?: 0f; val vbMinY = vb?.getOrNull(1) ?: 0f
    val vbW = vb?.getOrNull(2)?.takeIf { it != 0f } ?: 1f
    val vbH = vb?.getOrNull(3)?.takeIf { it != 0f } ?: 1f
    fun map(px: Float, py: Float) = (x + (px - vbMinX) / vbW * w) to (y + (py - vbMinY) / vbH * h)
    // Tokenize commands + numbers.
    val tokens = Regex("[MmLlHhVvCcSsQqTtAaZz]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
    val path = SvgPathAcc(tokens)
    while (path.i < tokens.size) {
        val tk = tokens[path.i]
        if (tk.length == 1 && tk[0].isLetter()) { path.cmd = tk[0]; path.i++ }
        val rel = path.cmd.isLowerCase()
        path.i = applySvgCommand(path.i, path.cmd, rel, path)
    }
    return path.raw.map { map(it.first, it.second) } to path.closed
}

/** SVG path parse state. */
private class SvgPathAcc(
    val tokens: List<String>,
    var i: Int = 0,
    var cx: Float = 0f,
    var cy: Float = 0f,
    var startX: Float = 0f,
    var startY: Float = 0f,
    var cmd: Char = ' ',
    var closed: Boolean = false,
    val raw: ArrayList<Pair<Float, Float>> = ArrayList(),
) {
    fun num(): Float = (tokens.getOrNull(i++)?.toFloatOrNull() ?: 0f)

    /** Next (x, y) pair, made absolute when the command is relative. */
    fun pair(relative: Boolean): Pair<Float, Float> {
        var nx = num()
        var ny = num()
        if (relative) {
            nx += cx
            ny += cy
        }
        return nx to ny
    }

    fun cubic(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val steps = CURVE_STEPS
        for (s in 1..steps) {
            val t = s / steps.toFloat(); val u = 1 - t
            val px = u * u * u * x0 + CUBIC_WEIGHT * u * u * t * x1 +
                CUBIC_WEIGHT * u * t * t * x2 + t * t * t * x3
            val py = u * u * u * y0 + CUBIC_WEIGHT * u * u * t * y1 +
                CUBIC_WEIGHT * u * t * t * y2 + t * t * t * y3
            raw.add(px to py)
        }
    }

    fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
        val steps = CURVE_STEPS
        for (s in 1..steps) {
            val t = s / steps.toFloat(); val u = 1 - t
            val px = u * u * x0 + 2 * u * t * x1 + t * t * x2
            val py = u * u * y0 + 2 * u * t * y1 + t * t * y2
            raw.add(px to py)
        }
    }
}

/** Apply one SVG path command; returns the new token index. */
private fun OdfParser.applySvgCommand(
    i: Int,
    cmd: Char,
    rel: Boolean,
    path: SvgPathAcc,
): Int {
    when (cmd.uppercaseChar()) {
        'M' -> applySvgMove(path, rel)
        'L' -> applySvgLine(path, rel)
        'H', 'V' -> applySvgAxis(path, cmd, rel)
        'C', 'S' -> applySvgCubic(path, cmd, rel)
        'Q', 'T' -> applySvgQuad(path, cmd, rel)
        'A' -> applySvgArc(path, rel)
        'Z' -> applySvgClose(path)
        // Unknown command: skip one token so the loop always makes progress.
        // (Callers pass path.i here, so returning i + 1 advances past the junk token.)
        else -> return i + 1
    }
    return path.i
}

private fun applySvgMove(path: SvgPathAcc, rel: Boolean) {
    val (nx, ny) = path.pair(rel)
    path.cx = nx
    path.cy = ny
    path.startX = path.cx
    path.startY = path.cy
    path.raw.add(path.cx to path.cy)
    path.cmd = if (rel) 'l' else 'L'
}

private fun applySvgLine(path: SvgPathAcc, rel: Boolean) {
    var nx = path.num()
    var ny = path.num()
    if (rel) {
        nx += path.cx
        ny += path.cy
    }
    path.cx = nx
    path.cy = ny
    path.raw.add(path.cx to path.cy)
}

private fun applySvgAxis(path: SvgPathAcc, cmd: Char, rel: Boolean) {
    if (cmd.uppercaseChar() == 'H') {
        var nx = path.num()
        if (rel) nx += path.cx
        path.cx = nx
    } else {
        var ny = path.num()
        if (rel) ny += path.cy
        path.cy = ny
    }
    path.raw.add(path.cx to path.cy)
}

private fun applySvgCubic(path: SvgPathAcc, cmd: Char, rel: Boolean) {
    if (cmd.uppercaseChar() == 'C') {
        val (x1, y1) = path.pair(rel)
        val (x2, y2) = path.pair(rel)
        val (nx, ny) = path.pair(rel)
        path.cubic(path.cx, path.cy, x1, y1, x2, y2, nx, ny)
        path.cx = nx
        path.cy = ny
    } else {
        val (x2, y2) = path.pair(rel)
        val (nx, ny) = path.pair(rel)
        path.cubic(path.cx, path.cy, path.cx, path.cy, x2, y2, nx, ny)
        path.cx = nx
        path.cy = ny
    }
}

private fun applySvgQuad(path: SvgPathAcc, cmd: Char, rel: Boolean) {
    if (cmd.uppercaseChar() == 'Q') {
        val (x1, y1) = path.pair(rel)
        val (nx, ny) = path.pair(rel)
        path.quad(path.cx, path.cy, x1, y1, nx, ny)
        path.cx = nx
        path.cy = ny
    } else {
        var nx = path.num()
        var ny = path.num()
        if (rel) {
            nx += path.cx
            ny += path.cy
        }
        path.raw.add(nx to ny)
        path.cx = nx
        path.cy = ny
    }
}

private fun applySvgArc(path: SvgPathAcc, rel: Boolean) {
    repeat(ARC_SKIP_COUNT) { path.num() }
    val (nx, ny) = path.pair(rel)
    path.raw.add(nx to ny)
    path.cx = nx
    path.cy = ny
}

private fun applySvgClose(path: SvgPathAcc) {
    path.closed = true
    path.raw.add(path.startX to path.startY)
}

/** Maps a draw:points string (viewBox space) into absolute px@96 vertices. (Priority 8) */
internal fun OdfParser.parsePolyPoints(
    raw: String?,
    vb: List<Float>?,
    x: Float,
    y: Float,
    w: Float,
    h: Float): List<Pair<Float, Float>> {
    if (raw.isNullOrBlank()) return emptyList()
    val vbMinX = vb?.getOrNull(0) ?: 0f; val vbMinY = vb?.getOrNull(1) ?: 0f
    val vbW = vb?.getOrNull(2)?.takeIf { it != 0f } ?: 1f; val vbH = vb?.getOrNull(3)?.takeIf { it != 0f } ?: 1f
    return raw.trim().split(Regex("\\s+")).mapNotNull { pair ->
        val xy = pair.split(","); if (xy.size != 2) return@mapNotNull null
        val vx = xy[0].toFloatOrNull() ?: return@mapNotNull null
        val vy = xy[1].toFloatOrNull() ?: return@mapNotNull null
        (x + (vx - vbMinX) / vbW * w) to (y + (vy - vbMinY) / vbH * h)
    }
}
