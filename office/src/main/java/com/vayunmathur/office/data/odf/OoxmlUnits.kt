package com.vayunmathur.office.data.odf

/**
 * Central unit conversions and color parsing for OOXML (Phase 0D). All screen lengths in the ODF
 * model are px@96, so these converge on px/pt where the model expects them.
 */
internal object OoxmlUnits {

    // --- Lengths ---

    private const val EMU_PER_PX = 9525f
    private const val EMU_PER_PT = 12700f
    private const val TWIPS_PER_PT = 20f
    private const val PX_PER_INCH = 96f
    private const val PT_PER_INCH = 72f
    private const val ANGLE_60K_PER_DEGREE = 60000f
    private const val EXCEL_CHAR_PX = 7f
    private const val EXCEL_PADDING_PX = 5f
    private const val OOXML_THOUSANDTHS = 100000f
    private const val CHANNEL_MAX = 255f
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE_MASK = 0xFFL
    private const val FULL_ALPHA = 0xFF000000L
    private const val HUNDREDTHS_PER_PT = 100f
    private const val CHANNEL_RANGE_MAX = 255
    private const val HUE_SECTORS = 6f
    private const val HUE_GREEN_OFFSET = 2f
    private const val HUE_BLUE_OFFSET = 4f
    private const val HUE_THIRD = 3f
    private const val RGB_HEX_LENGTH = 6
    private const val ARGB_HEX_LENGTH = 8
    private const val HEX_RADIX = 16

    /** EMU (English Metric Units, 914400/inch, 9525/px@96) -> px@96. */
    fun emuToPx(emu: Long): Float = emu / EMU_PER_PX

    /** EMU -> pt (72/inch). */
    fun emuToPt(emu: Long): Float = emu / EMU_PER_PT

    /** Twips (1/20 pt) -> pt. */
    fun twipsToPt(tw: Int): Float = tw / TWIPS_PER_PT

    /** Twips -> px@96 (1 pt = 96/72 px). */
    fun twipsToPx(tw: Int): Float = tw / TWIPS_PER_PT * PX_PER_INCH / PT_PER_INCH

    /** Half-points (w:sz, a:sz uses 1/100 pt instead) -> pt. */
    fun halfPtToPt(hp: Int): Float = hp / 2f

    /** Hundredths of a point (DrawingML a:sz, a:spc) -> pt. */
    fun hundredthPtToPt(v: Int): Float = v / HUNDREDTHS_PER_PT

    /** 60000ths of a degree (DrawingML rot) -> degrees clockwise. */
    fun angle60000ToDeg(v: Int): Float = v / ANGLE_60K_PER_DEGREE

    /** Excel column width (in "max digit widths") -> approximate px@96. */
    fun excelColWidthToPx(chars: Float): Float = ((chars * EXCEL_CHAR_PX) + EXCEL_PADDING_PX)

    /** Excel row height (points) -> px@96. */
    fun ptToPx(pt: Float): Float = pt * PX_PER_INCH / PT_PER_INCH

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
                RGB_HEX_LENGTH -> FULL_ALPHA or s.toLong(HEX_RADIX)
                ARGB_HEX_LENGTH -> s.toLong(HEX_RADIX)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private val HIGHLIGHT_COLORS = mapOf(
        "black" to 0xFF000000L, "blue" to 0xFF0000FFL, "cyan" to 0xFF00FFFFL,
        "green" to 0xFF008000L, "magenta" to 0xFFFF00FFL, "red" to 0xFFFF0000L,
        "yellow" to 0xFFFFFF00L, "white" to 0xFFFFFFFFL, "darkblue" to 0xFF000080L,
        "darkcyan" to 0xFF008080L, "darkgreen" to 0xFF006400L, "darkmagenta" to 0xFF800080L,
        "darkred" to 0xFF800000L, "darkyellow" to 0xFF808000L, "darkgray" to 0xFFA9A9A9L,
        "lightgray" to 0xFFD3D3D3L,
    )

    /** Standard highlight color names (w:highlight) -> 0xFFRRGGBB. */
    fun highlightColor(name: String?): Long? = HIGHLIGHT_COLORS[name?.lowercase()]

    private val SYS_COLORS = mapOf(
        "windowtext" to 0xFF000000L, "captiontext" to 0xFF000000L,
        "window" to 0xFFFFFFFFL, "graytext" to 0xFF808080L,
        "highlight" to 0xFF3399FFL, "btnface" to 0xFFF0F0F0L,
        "btntext" to 0xFF000000L,
    )

    /** Standard DrawingML/VML system color names (sysClr val) -> 0xFFRRGGBB. */
    fun sysColor(name: String?): Long? = SYS_COLORS[name?.lowercase()]

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
        var a = ((base ushr ALPHA_SHIFT) and BYTE_MASK).toInt()
        var r = ((base ushr RED_SHIFT) and BYTE_MASK).toInt().toFloat()
        var g = ((base ushr GREEN_SHIFT) and BYTE_MASK).toInt().toFloat()
        var b = (base and BYTE_MASK).toInt().toFloat()

        // shade: multiply toward black
        shade?.let { val f = it / OOXML_THOUSANDTHS; r *= f; g *= f; b *= f }
        // tint: interpolate toward white
        tint?.let {
            val f = it / OOXML_THOUSANDTHS
            val inv = CHANNEL_MAX * (1 - f)
            r = r * f + inv
            g = g * f + inv
            b = b * f + inv
        }

        if (lumMod != null || lumOff != null || satMod != null) {
            val hsl = rgbToHsl(r, g, b)
            var h = hsl[0]; var s = hsl[1]; var l = hsl[2]
            satMod?.let { s = (s * (it / OOXML_THOUSANDTHS)).coerceIn(0f, 1f) }
            lumMod?.let { l = (l * (it / OOXML_THOUSANDTHS)).coerceIn(0f, 1f) }
            lumOff?.let { l = (l + it / OOXML_THOUSANDTHS).coerceIn(0f, 1f) }
            val rgb = hslToRgb(h, s, l)
            r = rgb[0]; g = rgb[1]; b = rgb[2]
        }
        alpha?.let { a = (it / OOXML_THOUSANDTHS * CHANNEL_MAX).toInt().coerceIn(0, CHANNEL_RANGE_MAX) }

        return (a.toLong() shl ALPHA_SHIFT) or
            (r.toInt().coerceIn(0, CHANNEL_RANGE_MAX).toLong() shl RED_SHIFT) or
            (g.toInt().coerceIn(0, CHANNEL_RANGE_MAX).toLong() shl GREEN_SHIFT) or
            b.toInt().coerceIn(0, CHANNEL_RANGE_MAX).toLong()
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val rn = r / CHANNEL_MAX; val gn = g / CHANNEL_MAX; val bn = b / CHANNEL_MAX
        val max = maxOf(rn, gn, bn); val min = minOf(rn, gn, bn)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            rn -> (gn - bn) / d + (if (gn < bn) HUE_SECTORS else 0f)
            gn -> (bn - rn) / d + HUE_GREEN_OFFSET
            else -> (rn - gn) / d + HUE_BLUE_OFFSET
        } / HUE_SECTORS
        return floatArrayOf(h, s, l)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        if (s == 0f) { val v = l * CHANNEL_MAX; return floatArrayOf(v, v, v) }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        return floatArrayOf(
            hue2rgb(p, q, h + 1f / HUE_THIRD) * CHANNEL_MAX,
            hue2rgb(p, q, h) * CHANNEL_MAX,
            hue2rgb(p, q, h - 1f / HUE_THIRD) * CHANNEL_MAX)
    }

    private fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0) t += 1f
        if (t > 1) t -= 1f
        return when {
            t < 1f / HUE_SECTORS -> p + (q - p) * HUE_SECTORS * t
            t < 1f / 2f -> q
            t < HUE_GREEN_OFFSET / HUE_THIRD -> p + (q - p) * (HUE_GREEN_OFFSET / HUE_THIRD - t) * HUE_SECTORS
            else -> p
        }
    }
}
