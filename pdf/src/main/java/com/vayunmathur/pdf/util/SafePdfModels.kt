package com.vayunmathur.pdf.util

import androidx.compose.ui.geometry.Offset

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
    Normal(0), Multiply(1), Screen(2), Overlay(3), Darken(4), Lighten(5),
    ColorDodge(6), ColorBurn(7), HardLight(8), SoftLight(9), Difference(10),
    Exclusion(11), Hue(12), Saturation(13), Color(14), Luminosity(15);
    companion object {
        fun fromCode(c: Int): BlendMode = values().find { it.code == c } ?: Normal
    }
}

sealed interface PdfPrimitive {
    /** A run of text with its baseline origin, on-page size and color. Optional stroke for Tr modes 1,2,5,6 */
    data class Text(
        val origin: Offset,
        val size: Float,
        val color: Int,
        val text: String,
        val strokeColor: Int? = null,
        val strokeWidth: Float = 0f,
        val advance: Float = size * 0.5f * text.length,
    ) : PdfPrimitive

    /** A filled polygon (one subpath). */
    data class FillPath(
        val color: Int,
        val evenOdd: Boolean,
        val points: List<Offset>,
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
    ) : PdfPrimitive

    /** Push a clipping path (evenOdd true => EVEN_ODD else WINDING) - must be paired with ClipPop via save/restore */
    data class ClipPush(
        val evenOdd: Boolean,
        val points: List<Offset>,
    ) : PdfPrimitive

    /** Pop clipping - restores previous clip via canvas restore */
    data object ClipPop : PdfPrimitive

    /** Transparency group push - saveLayer with blend mode (v3) */
    data class GroupPush(
        val isolated: Boolean,
        val knockout: Boolean,
        val alpha: Float,
        val blend: BlendMode,
    ) : PdfPrimitive

    /** Pop transparency group - restores layer */
    data object GroupPop : PdfPrimitive
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
