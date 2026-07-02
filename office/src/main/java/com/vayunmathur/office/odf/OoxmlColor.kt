package com.vayunmathur.office.odf

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
        val base: Long? = when (tag) {
            "srgbClr" -> OoxmlUnits.hexColor(OoxmlXml.attr(parser, "val"))
            "sysClr" -> OoxmlUnits.hexColor(OoxmlXml.attr(parser, "lastClr")) ?: OoxmlUnits.sysColor(OoxmlXml.attr(parser, "val"))
            "schemeClr" -> theme.schemeColor(OoxmlXml.attr(parser, "val"))
            "prstClr" -> PRESET[OoxmlXml.attr(parser, "val")]
            "scrgbClr" -> scrgb(OoxmlXml.attr(parser, "r"), OoxmlXml.attr(parser, "g"), OoxmlXml.attr(parser, "b"))
            else -> null
        }
        var lumMod: Int? = null; var lumOff: Int? = null
        var tint: Int? = null; var shade: Int? = null; var satMod: Int? = null; var alpha: Int? = null
        val depth = parser.depth
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == tag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) {
                val v = OoxmlXml.attr(parser, "val")?.toIntOrNull()
                when (parser.name) {
                    "lumMod" -> lumMod = v; "lumOff" -> lumOff = v
                    "tint" -> tint = v; "shade" -> shade = v
                    "satMod" -> satMod = v; "alpha" -> alpha = v
                }
            }
            e = parser.next()
        }
        if (base == null) return null
        val hasTransform = lumMod != null || lumOff != null || tint != null || shade != null || satMod != null || alpha != null
        return if (hasTransform) OoxmlUnits.applyTransforms(base, lumMod, lumOff, tint, shade, satMod, alpha) else base
    }

    private fun scrgb(r: String?, g: String?, b: String?): Long? {
        fun pct(s: String?): Int? = s?.toIntOrNull()?.let { (it / 100000f * 255f).toInt().coerceIn(0, 255) }
        val rr = pct(r) ?: return null; val gg = pct(g) ?: return null; val bb = pct(b) ?: return null
        return 0xFF000000L or (rr.toLong() shl 16) or (gg.toLong() shl 8) or bb.toLong()
    }
}
