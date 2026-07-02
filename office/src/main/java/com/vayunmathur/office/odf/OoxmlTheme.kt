package com.vayunmathur.office.odf

import org.xmlpull.v1.XmlPullParser

/**
 * Resolved DrawingML theme (Phase 0C): the color scheme (dk1/lt1/dk2/lt2/accent1-6/hlink/folHlink)
 * and the major/minor Latin fonts. Used to resolve `a:schemeClr` references and default fonts.
 */
internal class OoxmlTheme(
    val colors: Map<String, Long>,
    val majorFont: String?,
    val minorFont: String?
) {
    /**
     * Resolves a scheme color name (as it appears in `a:schemeClr val`, incl. tx1/bg1/tx2/bg2
     * aliases and dk1/lt1/...) to an 0xFFRRGGBB base, or null. `phClr` returns null (context color).
     */
    fun schemeColor(name: String?): Long? {
        val key = when (name?.lowercase()) {
            "tx1", "dk1" -> "dk1"
            "bg1", "lt1" -> "lt1"
            "tx2", "dk2" -> "dk2"
            "bg2", "lt2" -> "lt2"
            null, "phclr" -> return null
            else -> name.lowercase()
        }
        return colors[key]
    }

    companion object {
        val DEFAULT = OoxmlTheme(
            colors = mapOf(
                "dk1" to 0xFF000000, "lt1" to 0xFFFFFFFF, "dk2" to 0xFF44546A, "lt2" to 0xFFE7E6E6,
                "accent1" to 0xFF4472C4, "accent2" to 0xFFED7D31, "accent3" to 0xFFA5A5A5,
                "accent4" to 0xFFFFC000, "accent5" to 0xFF5B9BD5, "accent6" to 0xFF70AD47,
                "hlink" to 0xFF0563C1, "folhlink" to 0xFF954F72
            ),
            majorFont = "Calibri Light", minorFont = "Calibri"
        )

        /** Parses a theme1.xml part; falls back to [DEFAULT] entries for anything missing. */
        fun parse(xml: String?): OoxmlTheme {
            if (xml == null) return DEFAULT
            val colors = HashMap<String, Long>()
            var majorFont: String? = null
            var minorFont: String? = null
            val parser = OoxmlXml.newParser(xml)
            var e = parser.eventType
            var inClrScheme = false
            var clrSchemeDepth = -1
            var inMajor = false
            var inMinor = false
            var currentSlot: String? = null
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG) {
                    val n = parser.name
                    when (n) {
                        "clrScheme" -> { inClrScheme = true; clrSchemeDepth = parser.depth }
                        "majorFont" -> inMajor = true
                        "minorFont" -> inMinor = true
                        "latin" -> {
                            val tf = OoxmlXml.attr(parser, "typeface")
                            if (inMajor && majorFont == null) majorFont = tf
                            if (inMinor && minorFont == null) minorFont = tf
                        }
                        "srgbClr", "sysClr" -> {
                            val slot = currentSlot
                            if (inClrScheme && slot != null) {
                                val v = if (n == "srgbClr") OoxmlXml.attr(parser, "val")
                                else OoxmlXml.attr(parser, "lastClr") ?: OoxmlUnits.sysColor(OoxmlXml.attr(parser, "val"))?.let { "%06X".format(it and 0xFFFFFF) }
                                OoxmlUnits.hexColor(v)?.let { colors[slot.lowercase()] = it }
                                currentSlot = null
                            }
                        }
                        else -> if (inClrScheme && parser.depth == clrSchemeDepth + 1) currentSlot = n
                    }
                } else if (e == XmlPullParser.END_TAG) when (parser.name) {
                    "clrScheme" -> inClrScheme = false
                    "majorFont" -> inMajor = false
                    "minorFont" -> inMinor = false
                }
                e = parser.next()
            }
            // Fill any missing slots from DEFAULT so schemeColor never returns null unexpectedly.
            for ((k, v) in DEFAULT.colors) colors.putIfAbsent(k, v)
            return OoxmlTheme(colors, majorFont ?: DEFAULT.majorFont, minorFont ?: DEFAULT.minorFont)
        }
    }
}
