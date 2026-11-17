package com.vayunmathur.pdf.platform

import androidx.compose.ui.geometry.Offset

/** Heuristic advance per glyph when the wire carries no device-space advance. */
private const val ADVANCE_PER_GLYPH = 0.5f

private const val BLEND_NORMAL = 0
private const val BLEND_MULTIPLY = 1
private const val BLEND_SCREEN = 2
private const val BLEND_OVERLAY = 3
private const val BLEND_DARKEN = 4
private const val BLEND_LIGHTEN = 5
private const val BLEND_COLOR_DODGE = 6
private const val BLEND_COLOR_BURN = 7
private const val BLEND_HARD_LIGHT = 8
private const val BLEND_SOFT_LIGHT = 9
private const val BLEND_DIFFERENCE = 10
private const val BLEND_EXCLUSION = 11
private const val BLEND_HUE = 12
private const val BLEND_SATURATION = 13
private const val BLEND_COLOR = 14
private const val BLEND_LUMINOSITY = 15

/**
 * A single drawing primitive decoded from the native renderer, in PDF page
 * space (origin bottom-left). [SafePdfViewerScreen] applies the Y-flip and the
 * fit-to-width scale when drawing.
 *
 * Colors are packed ARGB ([Int]) matching Android's color ints.
 *
 * Wire v2 adds: cap/join/miter for StrokePath, stroke for Text, ClipPush/Pop.
 * Wire v3 adds: GroupPush/Pop with blend modes (transparency groups), accurate
 * text advance for search alignment.
 */
enum class BlendMode(val code: Int) {
    Normal(BLEND_NORMAL),
    Multiply(BLEND_MULTIPLY),
    Screen(BLEND_SCREEN),
    Overlay(BLEND_OVERLAY),
    Darken(BLEND_DARKEN),
    Lighten(BLEND_LIGHTEN),
    ColorDodge(BLEND_COLOR_DODGE),
    ColorBurn(BLEND_COLOR_BURN),
    HardLight(BLEND_HARD_LIGHT),
    SoftLight(BLEND_SOFT_LIGHT),
    Difference(BLEND_DIFFERENCE),
    Exclusion(BLEND_EXCLUSION),
    Hue(BLEND_HUE),
    Saturation(BLEND_SATURATION),
    Color(BLEND_COLOR),
    Luminosity(BLEND_LUMINOSITY);

    /** Shared constants and helpers for BlendMode. */
    companion object {
        private val CODE_MAP: Map<Int, BlendMode> = entries.associateBy { it.code }
        fun fromCode(c: Int): BlendMode = CODE_MAP[c] ?: Normal
    }
}

/** Contract for pdf primitive. */
sealed interface PdfPrimitive {
    /**
     * A run of text with its baseline origin, on-page size and color. Optional
     * stroke for Tr modes 1,2,5,6. [renderMode] is the PDF text render mode (Tr):
     * 0 fill, 1 stroke, 2 fill+stroke, 3 invisible, 4-6 = 0-2 plus clip, 7 clip.
     * v8 adds isBold/isItalic recovered from font BaseFont/FontDescriptor, plus
     * [hScale]. [fontFamily] selects the substitute system typeface: 0 sans-serif,
     * 1 serif, 2 monospace (also from BaseFont/FontDescriptor).
     */
    data class Text(
        val origin: Offset,
        val size: Float,
        val color: Int,
        val text: String,
        val strokeColor: Int? = null,
        val strokeWidth: Float = 0f,
        val advance: Float = size * ADVANCE_PER_GLYPH * text.length,
        val renderMode: Int = 0,
        val blend: BlendMode = BlendMode.Normal,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val fontFamily: Int = 0,
        /// True when the glyph was already drawn via Fill prims (embedded-font
        /// outline rendering); kept only for text selection/search, not painted.
        val outline: Boolean = false,
        /**
         * Horizontal scale for the substitute face, applied as `Paint.textScaleX`.
         *
         * NOT the Tz percentage the name suggests: `draw.rs`'s `show_string_in` sends
         * `Th * x_scale / y_scale` (its `wire_h_scale`), because [size] carries only the
         * text matrix's Y
         * scale, so an anisotropic matrix has nowhere else to put its X/Y ratio. Exactly
         * 1.0 for every isotropic matrix, rotations included, so ordinary pages are
         * unaffected; a `4 0 0 1 0 0 cm` stretch sends 4.0.
         */
        val hScale: Float = 1f,
    ) : PdfPrimitive

    /**
     * A filled region made of one or more closed [contours]. Interior contours
     * (glyph counters / holes) are cut out by the even-odd or nonzero winding
     * rule when all contours are filled as a single path.
     */
    data class FillPath(
        val color: Int,
        val evenOdd: Boolean,
        val contours: List<List<Offset>>,
        val blend: BlendMode = BlendMode.Normal,
    ) : PdfPrimitive

    /** A stroked polyline (one subpath). [dash] is empty for a solid line. */
    data class StrokePath(
        val color: Int,
        val width: Float,
        val dash: FloatArray,
        val dashPhase: Float,
        val points: List<Offset>,
        val cap: Int = 0,
        val join: Int = 0,
        val miter: Float = 10f,
        val blend: BlendMode = BlendMode.Normal,
    ) : PdfPrimitive

    /**
     * A raster image. [ctm] is the 6-element PDF matrix (a,b,c,d,e,f) mapping
     * the unit square to page space; [bitmap] is the decoded image (null if it
     * could not be decoded). [alpha] allows transparent images (soft-masks).
     */
    data class Image(
        val ctm: FloatArray,
        val bitmap: android.graphics.Bitmap?,
        val alpha: Float = 1f,
        val blend: BlendMode = BlendMode.Normal,
        /**
         * Whether to smooth the image when it is scaled up (wire v11). PDF 32000-1 §8.9.5.1
         * Table 89: `/Interpolate` defaults to **false**, and bilevel art must never be
         * smoothed — Rust decides this per image in `image_should_interpolate`. Defaults to
         * true so an older wire behaves exactly as before the flag existed.
         */
        val interpolate: Boolean = true,
    ) : PdfPrimitive

    /**
     * A tiling pattern drawn as ONE repeating cell rather than one [Image] per tile (wire
     * tag 14). [ctm] maps the unit square onto one cell — one period of the lattice — so the
     * bitmap's own dimensions ARE the repeat period; Rust rasterizes the cell at `/XStep` x
     * `/YStep`, not at the pattern `/BBox`, with transparent padding where the step is larger.
     * [xstep]/[ystep] are carried only so the renderer can assert that correspondence.
     *
     * [i0]/[j0]/[nx]/[ny] are the lattice extent in cell indices relative to [ctm]'s origin.
     * A `REPEAT` shader tiles infinitely, so these only determine the region to fill.
     *
     * Only emitted for NON-overlapping patterns: PDF 32000-1 §8.7.3.1 permits `/XStep` smaller
     * than the BBox with later tiles painted over earlier, and a periodic repeat cannot express
     * overlap, so Rust routes that case to the per-tile path instead.
     */
    data class ImageTiled(
        val ctm: FloatArray,
        val bitmap: android.graphics.Bitmap?,
        val xstep: Float,
        val ystep: Float,
        val i0: Int,
        val j0: Int,
        val nx: Int,
        val ny: Int,
        val alpha: Float = 1f,
        val blend: BlendMode = BlendMode.Normal,
    ) : PdfPrimitive

    /**
     * Push a clipping path (evenOdd true => EVEN_ODD else WINDING) - must be
     * paired with ClipPop via save/restore. [points] is the flattened polyline
     * (v2/v3); [pathOps] carries the bezier-retentive path (v4) for accurate
     * curved clips, and is preferred when present.
     */
    data class ClipPush(
        val evenOdd: Boolean,
        val points: List<Offset>,
        val pathOps: List<PathOp>? = null,
    ) : PdfPrimitive

    /** Pop clipping - restores previous clip via canvas restore */
    data object ClipPop : PdfPrimitive

    /**
     * Marker (v4): intersect the accumulated glyph outlines of the just-ended
     * text object (Tr clip modes 4-7) into the clip. Paired with a later ClipPop.
     */
    data object TextClipApply : PdfPrimitive

    /** Transparency group push - saveLayer with blend mode (v3) */
    data class GroupPush(
        val isolated: Boolean,
        val knockout: Boolean,
        val alpha: Float,
        val blend: BlendMode,
    ) : PdfPrimitive

    /** Pop transparency group - restores layer */
    data object GroupPop : PdfPrimitive

    /**
     * Begin an ExtGState soft-masked region (v5). [maskType] is 0 for an alpha
     * mask or 1 for a luminosity mask. The primitives up to [SoftMaskContent]
     * are the masked content; the primitives from [SoftMaskContent] to
     * [SoftMaskPop] are the mask itself.
     */
    data class SoftMaskPush(val maskType: Int) : PdfPrimitive

    /** Marker: switch from drawing the masked content to drawing the mask (v5). */
    data object SoftMaskContent : PdfPrimitive

    /**
     * The /TR transfer function of the enclosing [SoftMaskPush] (wire tag 13), reduced to
     * `gain * m + bias` over the mask value m in 0..1. [affine] is false when the transmitted
     * 256-entry LUT is too far from a straight line for that form to represent it, in which
     * case the mask is left untransformed — see [SafePdfParser] for why an affine fit is the
     * only shape that can be applied to a Canvas layer.
     */
    data class SoftMaskTransfer(
        val gain: Float,
        val bias: Float,
        val affine: Boolean,
    ) : PdfPrimitive

    /** End a soft-masked region: composite the mask onto the content (v5). */
    data object SoftMaskPop : PdfPrimitive
}

/** A bezier-retentive clip path operation (wire v4), in page space. */
sealed interface PathOp {
    /** Immutable value describing move; carries x, y. */
    data class Move(val x: Float, val y: Float) : PathOp
    /** Immutable value describing line; carries x, y. */
    data class Line(val x: Float, val y: Float) : PathOp
    /** Immutable value describing cubic. */
    data class Cubic(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val x3: Float, val y3: Float,
    ) : PathOp
    /** Singleton holding close state and operations. */
    data object Close : PathOp
}

/** One decoded page: its PDF page dimensions plus the primitives to draw. */
data class SafePdfPage(
    val width: Float,
    val height: Float,
    val primitives: List<PdfPrimitive>,
)

/** An annotation on a page (from the native listing), in page space. */
data class SafeAnnotation(
    val id: Long,
    val subtype: Int, // 1 FreeText, 2 Highlight, 3 Square, 4 Ink, 5 Stamp, 6 Widget, ...
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val color: Int,
    val contents: String,
)

/** An AcroForm widget field on a page, in page space. */
data class SafeFormField(
    val id: Long,
    val type: Int, // 0 text, 1 checkbox/button, 2 choice, 3 other
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val name: String,
    val value: String,
    val checked: Boolean,
)

/** One entry in the document outline (bookmarks). */
data class SafeOutlineItem(
    val level: Int,
    val page: Int,
    val title: String,
)

/** A search hit: page index + bounding rect in page space. */
data class SafeSearchMatch(
    val page: Int,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
)

/** A link annotation: page-space rect plus a destination page (-1 if none) and/or URI. */
data class SafeLink(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val destPage: Int,
    val uri: String,
)
