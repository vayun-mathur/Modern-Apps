package com.vayunmathur.office.odf

/**
 * Central unit conversions and color parsing for OOXML (Phase 0D). All screen lengths in the ODF
 * model are px@96, so these converge on px/pt where the model expects them.
 */
internal object OoxmlUnits {

    // --- Lengths ---

    /** EMU (English Metric Units, 914400/inch, 9525/px@96) -> px@96. */
    fun emuToPx(emu: Long): Float = emu / 9525f

    /** EMU -> pt (72/inch). */
    fun emuToPt(emu: Long): Float = emu / 12700f

    /** Twips (1/20 pt) -> pt. */
    fun twipsToPt(tw: Int): Float = tw / 20f

    /** Twips -> px@96 (1 pt = 96/72 px). */
    fun twipsToPx(tw: Int): Float = tw / 20f * 96f / 72f

    /** Half-points (w:sz, a:sz uses 1/100 pt instead) -> pt. */
    fun halfPtToPt(hp: Int): Float = hp / 2f

    /** Hundredths of a point (DrawingML a:sz, a:spc) -> pt. */
    fun hundredthPtToPt(v: Int): Float = v / 100f

    /** 60000ths of a degree (DrawingML rot) -> degrees clockwise. */
    fun angle60000ToDeg(v: Int): Float = v / 60000f

    /** Excel column width (in "max digit widths") -> approximate px@96. */
    fun excelColWidthToPx(chars: Float): Float = ((chars * 7f) + 5f)

    /** Excel row height (points) -> px@96. */
    fun ptToPx(pt: Float): Float = pt * 96f / 72f

    // --- Colors ---

    /**
     * Parses a raw 6- or 8-hex color string (RRGGBB or AARRGGBB) to 0xAARRGGBB, forcing full
     * alpha when only RGB is given. Returns null for "auto"/blank/invalid.
     */
    fun hexColor(v: String?): Long? {
        if (v == null) return null
        val s = v.trim().removePrefix("#")
        if (s.isEmpty() || s.equals("auto", true)) return null
        return try {
            when (s.length) {
                6 -> 0xFF000000L or s.toLong(16)
                8 -> s.toLong(16)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /** Standard highlight color names (w:highlight) -> 0xFFRRGGBB. */
    fun highlightColor(name: String?): Long? = when (name?.lowercase()) {
        "black" -> 0xFF000000; "blue" -> 0xFF0000FF; "cyan" -> 0xFF00FFFF
        "green" -> 0xFF008000; "magenta" -> 0xFFFF00FF; "red" -> 0xFFFF0000
        "yellow" -> 0xFFFFFF00; "white" -> 0xFFFFFFFF; "darkblue" -> 0xFF000080
        "darkcyan" -> 0xFF008080; "darkgreen" -> 0xFF006400; "darkmagenta" -> 0xFF800080
        "darkred" -> 0xFF800000; "darkyellow" -> 0xFF808000; "darkgray" -> 0xFFA9A9A9
        "lightgray" -> 0xFFD3D3D3; else -> null
    }

    /** Standard DrawingML/VML system color names (sysClr val) -> 0xFFRRGGBB. */
    fun sysColor(name: String?): Long? = when (name?.lowercase()) {
        "windowtext", "captiontext" -> 0xFF000000
        "window" -> 0xFFFFFFFF
        "graytext" -> 0xFF808080
        "highlight" -> 0xFF3399FF
        "btnface" -> 0xFFF0F0F0
        "btntext" -> 0xFF000000
        else -> null
    }

    /**
     * Applies DrawingML color transforms to an 0xAARRGGBB base:
     * lumMod/lumOff (luminance modulate/offset), tint (toward white), shade (toward black),
     * satMod (saturation modulate). Values are given as OOXML 1000ths (e.g. 60000 = 60%).
     */
    fun applyTransforms(
        base: Long,
        lumMod: Int? = null, lumOff: Int? = null,
        tint: Int? = null, shade: Int? = null, satMod: Int? = null,
        alpha: Int? = null
    ): Long {
        var a = ((base ushr 24) and 0xFF).toInt()
        var r = ((base ushr 16) and 0xFF).toInt().toFloat()
        var g = ((base ushr 8) and 0xFF).toInt().toFloat()
        var b = (base and 0xFF).toInt().toFloat()

        // shade: multiply toward black
        shade?.let { val f = it / 100000f; r *= f; g *= f; b *= f }
        // tint: interpolate toward white
        tint?.let { val f = it / 100000f; r = r * f + 255f * (1 - f); g = g * f + 255f * (1 - f); b = b * f + 255f * (1 - f) }

        if (lumMod != null || lumOff != null || satMod != null) {
            val hsl = rgbToHsl(r, g, b)
            var h = hsl[0]; var s = hsl[1]; var l = hsl[2]
            satMod?.let { s = (s * (it / 100000f)).coerceIn(0f, 1f) }
            lumMod?.let { l = (l * (it / 100000f)).coerceIn(0f, 1f) }
            lumOff?.let { l = (l + it / 100000f).coerceIn(0f, 1f) }
            val rgb = hslToRgb(h, s, l)
            r = rgb[0]; g = rgb[1]; b = rgb[2]
        }
        alpha?.let { a = (it / 100000f * 255f).toInt().coerceIn(0, 255) }

        return (a.toLong() shl 24) or
            (r.toInt().coerceIn(0, 255).toLong() shl 16) or
            (g.toInt().coerceIn(0, 255).toLong() shl 8) or
            b.toInt().coerceIn(0, 255).toLong()
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val rn = r / 255f; val gn = g / 255f; val bn = b / 255f
        val max = maxOf(rn, gn, bn); val min = minOf(rn, gn, bn)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            rn -> (gn - bn) / d + (if (gn < bn) 6f else 0f)
            gn -> (bn - rn) / d + 2f
            else -> (rn - gn) / d + 4f
        } / 6f
        return floatArrayOf(h, s, l)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        if (s == 0f) { val v = l * 255f; return floatArrayOf(v, v, v) }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        return floatArrayOf(hue2rgb(p, q, h + 1f / 3f) * 255f, hue2rgb(p, q, h) * 255f, hue2rgb(p, q, h - 1f / 3f) * 255f)
    }

    private fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0) t += 1f
        if (t > 1) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
}
