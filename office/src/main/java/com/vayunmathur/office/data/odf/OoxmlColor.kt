package com.vayunmathur.office.data.odf

import org.xmlpull.v1.XmlPullParser

/**
 * Resolves a DrawingML color element (`a:srgbClr` / `a:sysClr` / `a:schemeClr` / `a:prstClr` /
 * `a:scrgbClr`) together with its child transforms (lumMod/lumOff/tint/shade/satMod/alpha) to an
 * 0xAARRGGBB value, using [OoxmlTheme] to resolve scheme references. (Phase 0C/0D)
 */
internal object OoxmlColor {

    /** Preset color names (`a:prstClr val`) -> 0xFFRRGGBB (common subset). */
    private val PRESET = mapOf(
        "black" to 0xFF000000, "white" to 0xFFFFFFFF, "red" to 0xFFFF0000, "green" to 0xFF008000,
        "blue" to 0xFF0000FF, "yellow" to 0xFFFFFF00, "cyan" to 0xFF00FFFF, "magenta" to 0xFFFF00FF,
        "gray" to 0xFF808080, "grey" to 0xFF808080, "darkGray" to 0xFFA9A9A9, "lightGray" to 0xFFD3D3D3,
        "orange" to 0xFFFFA500, "purple" to 0xFF800080, "brown" to 0xFFA52A2A, "pink" to 0xFFFFC0CB
    )

    /**
     * The parser must be positioned on a color element START_TAG. Consumes through its END_TAG and
     * returns the resolved color, or null if the base color can't be resolved (e.g. bare phClr).
     */
    fun parse(parser: XmlPullParser, theme: OoxmlTheme): Long? {
        val tag = parser.name
        val base: Long? = baseColor(parser, theme, tag)
        val t = readTransforms(parser, tag)
        if (base == null) return null
        val hasTransform =
            t.lumMod != null || t.lumOff != null || t.tint != null ||
            t.shade != null || t.satMod != null || t.alpha != null
        return if (hasTransform) {
            OoxmlUnits.applyTransforms(
                base, t.lumMod, t.lumOff, t.tint, t.shade, t.satMod, t.alpha)
        } else {
            base
        }
    }

    /** Base color from the element tag + attributes. */
    private fun baseColor(parser: XmlPullParser, theme: OoxmlTheme, tag: String): Long? = when (tag) {
        "srgbClr" -> OoxmlUnits.hexColor(OoxmlXml.attr(parser, "val"))
        "sysClr" -> OoxmlUnits.hexColor(OoxmlXml.attr(
            parser,
            "lastClr")) ?: OoxmlUnits.sysColor(OoxmlXml.attr(parser, "val"))
        "schemeClr" -> theme.schemeColor(OoxmlXml.attr(parser, "val"))
        "prstClr" -> PRESET[OoxmlXml.attr(parser, "val")]
        "scrgbClr" -> scrgb(OoxmlXml.attr(parser, "r"), OoxmlXml.attr(parser, "g"), OoxmlXml.attr(parser, "b"))
        else -> null
    }

    /** Color transforms. */
    private class Transforms(
        var lumMod: Int? = null,
        var lumOff: Int? = null,
        var tint: Int? = null,
        var shade: Int? = null,
        var satMod: Int? = null,
        var alpha: Int? = null,
    )

    /** Transform children of a color element. */
    private fun readTransforms(parser: XmlPullParser, tag: String): Transforms {
        val t = Transforms()
        val depth = parser.depth
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == tag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) {
                val v = OoxmlXml.attr(parser, "val")?.toIntOrNull()
                when (parser.name) {
                    "lumMod" -> t.lumMod = v; "lumOff" -> t.lumOff = v
                    "tint" -> t.tint = v; "shade" -> t.shade = v
                    "satMod" -> t.satMod = v; "alpha" -> t.alpha = v
                }
            }
            e = parser.next()
        }
        return t
    }

    private const val OOXML_THOUSANDTHS = 100000f
    private const val CHANNEL_MAX = 255f
    private const val CHANNEL_RANGE = 255
    private const val FULL_ALPHA = 0xFF000000L
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    private fun scrgb(r: String?, g: String?, b: String?): Long? {
        fun pct(s: String?): Int? = s?.toIntOrNull()?.let {
            (it / OOXML_THOUSANDTHS * CHANNEL_MAX).toInt().coerceIn(0, CHANNEL_RANGE)
        }
        val rr = pct(r) ?: return null
        val gg = pct(g) ?: return null
        val bb = pct(b) ?: return null
        return FULL_ALPHA or (rr.toLong() shl RED_SHIFT) or (gg.toLong() shl GREEN_SHIFT) or bb.toLong()
    }
}
