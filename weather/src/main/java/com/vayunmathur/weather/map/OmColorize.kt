package com.vayunmathur.weather.map

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.vayunmathur.weather.util.ColorStop

/**
 * Colorize a row-major [values] grid (`w × h`, from
 * [OmTilesNative.decodeRegion]) into an opaque [ImageBitmap] using [ramp].
 * `NaN` values become fully transparent so gaps in coverage show the basemap.
 * The overall overlay translucency is applied by the `RasterLayer`, so pixels
 * here are fully opaque.
 */
fun colorizeToBitmap(values: FloatArray, w: Int, h: Int, ramp: List<ColorStop>): ImageBitmap {
    val pixels = IntArray(w * h)
    for (i in values.indices) {
        pixels[i] = rampColor(values[i], ramp)
    }
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    return bitmap.asImageBitmap()
}

/** ARGB color for a raw metric [value] on [ramp]; transparent when NaN. */
private fun rampColor(value: Float, ramp: List<ColorStop>): Int {
    if (value.isNaN() || ramp.isEmpty()) return 0
    val v = value.toDouble()
    if (v <= ramp.first().value) return ramp.first().color.toArgb()
    if (v >= ramp.last().value) return ramp.last().color.toArgb()
    for (i in 1 until ramp.size) {
        val hi = ramp[i]
        if (v <= hi.value) {
            val lo = ramp[i - 1]
            val t = ((v - lo.value) / (hi.value - lo.value)).toFloat()
            return lerpArgb(lo.color.toArgb(), hi.color.toArgb(), t)
        }
    }
    return ramp.last().color.toArgb()
}

/** Linear blend of two opaque ARGB colors; result is opaque. */
private fun lerpArgb(a: Int, b: Int, t: Float): Int {
    val ar = (a ushr 16) and 0xFF
    val ag = (a ushr 8) and 0xFF
    val ab = a and 0xFF
    val br = (b ushr 16) and 0xFF
    val bg = (b ushr 8) and 0xFF
    val bb = b and 0xFF
    val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
    val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
    val bl = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}
