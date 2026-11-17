package com.vayunmathur.office.data.odf

import org.xmlpull.v1.XmlPullParser

/**
 * Resolved DrawingML theme (Phase 0C): the color scheme (dk1/lt1/dk2/lt2/accent1-6/hlink/folHlink)
 * and the major/minor Latin fonts. Used to resolve `a:schemeClr` references and default fonts.
 */
internal class OoxmlTheme(
    val colors: Map<String, Long>,
    val majorFont: String?,
    val minorFont: String?,
    // Slide-master color map (bg1/tx1/bg2/tx2/accent* -> a theme slot dk1/lt1/...). Empty = defaults.
    val clrMap: Map<String, String> = emptyMap()
) {
    /**
     * Resolves a scheme color name (as it appears in `a:schemeClr val`, incl. tx1/bg1/tx2/bg2
     * aliases and dk1/lt1/...) to an 0xFFRRGGBB base, or null. `phClr` returns null (context color).
     */
    fun schemeColor(name: String?): Long? {
        val lc = name?.lowercase() ?: return null
        if (lc == "phclr") return null
        // Apply the slide master's <p:clrMap> for the placeholder aliases (can invert on dark templates);
        // fall back to the standard tx1->dk1 / bg1->lt1 mapping when no map is present.
        val key = clrMap[lc] ?: when (lc) {
            "tx1" -> "dk1"; "bg1" -> "lt1"; "tx2" -> "dk2"; "bg2" -> "lt2"
            else -> lc
        }
        return colors[key]
    }

    /** Returns a copy of this theme with the given slide-master color map applied. */
    fun withClrMap(map: Map<String, String>): OoxmlTheme =
        if (map.isEmpty()) this else OoxmlTheme(colors, majorFont, minorFont, map)

    companion object {
        private const val RGB_MASK = 0xFFFFFFL
        val DEFAULT = OoxmlTheme(
            colors = mapOf(
                "dk1" to 0xFF000000, "lt1" to 0xFFFFFFFF, "dk2" to 0xFF44546A, "lt2" to 0xFFE7E6E6,
                "accent1" to 0xFF4472C4, "accent2" to 0xFFED7D31, "accent3" to 0xFFA5A5A5,
                "accent4" to 0xFFFFC000, "accent5" to 0xFF5B9BD5, "accent6" to 0xFF70AD47,
                "hlink" to 0xFF0563C1, "folhlink" to 0xFF954F72
            ),
            majorFont = "Calibri Light", minorFont = "Calibri"
        )

        /** System color slot as hex, or null when unresolvable. */
        private fun sysHex(parser: org.xmlpull.v1.XmlPullParser): String? =
            OoxmlUnits.sysColor(OoxmlXml.attr(parser, "val"))?.let { "%06X".format(it and RGB_MASK) }

        /** Parses a theme1.xml part; falls back to [DEFAULT] entries for anything missing. */
        fun parse(xml: String?): OoxmlTheme {
            if (xml == null) return DEFAULT
            val acc = ThemeAcc()
            val parser = OoxmlXml.newParser(xml)
            var e = parser.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG) applyThemeTag(parser, acc)
                else if (e == XmlPullParser.END_TAG) applyThemeEndTag(parser, acc)
                e = parser.next()
            }
            // Fill any missing slots from DEFAULT so schemeColor never returns null unexpectedly.
            for ((k, v) in DEFAULT.colors) acc.colors.putIfAbsent(k, v)
            return OoxmlTheme(acc.colors, acc.majorFont ?: DEFAULT.majorFont, acc.minorFont ?: DEFAULT.minorFont)
        }

    private class ThemeAcc(
        val colors: HashMap<String, Long> = HashMap(),
        var majorFont: String? = null,
        var minorFont: String? = null,
        var inClrScheme: Boolean = false,
        var clrSchemeDepth: Int = -1,
        var inMajor: Boolean = false,
        var inMinor: Boolean = false,
        var currentSlot: String? = null,
    )

    private fun applyThemeTag(parser: XmlPullParser, acc: ThemeAcc) {
        when (parser.name) {
            "clrScheme" -> { acc.inClrScheme = true; acc.clrSchemeDepth = parser.depth }
            "majorFont" -> acc.inMajor = true
            "minorFont" -> acc.inMinor = true
            "latin" -> applyThemeFont(parser, acc)
            "srgbClr", "sysClr" -> applyThemeColor(parser, acc)
            else -> if (acc.inClrScheme && parser.depth == acc.clrSchemeDepth + 1) acc.currentSlot = parser.name
        }
    }

    private fun applyThemeFont(parser: XmlPullParser, acc: ThemeAcc) {
        val tf = OoxmlXml.attr(parser, "typeface")
        if (acc.inMajor && acc.majorFont == null) acc.majorFont = tf
        if (acc.inMinor && acc.minorFont == null) acc.minorFont = tf
    }

    private fun applyThemeColor(parser: XmlPullParser, acc: ThemeAcc) {
        val slot = acc.currentSlot ?: return
        if (!acc.inClrScheme) return
        val isSrgb = parser.name == "srgbClr"
        val v = if (isSrgb) OoxmlXml.attr(parser, "val") else OoxmlXml.attr(parser, "lastClr") ?: sysHex(parser)
        OoxmlUnits.hexColor(v)?.let { acc.colors[slot.lowercase()] = it }
        acc.currentSlot = null
    }

    private fun applyThemeEndTag(parser: XmlPullParser, acc: ThemeAcc) {
        when (parser.name) {
            "clrScheme" -> acc.inClrScheme = false
            "majorFont" -> acc.inMajor = false
            "minorFont" -> acc.inMinor = false
        }
    }
    }
}
