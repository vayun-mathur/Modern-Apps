package com.vayunmathur.photos.domain

/**
 * One circular mask dab: paints [paintValue] into [data] for the pixels whose
 * offset from the dab centre falls inside the dab circle.
 */
internal class MaskDab(
    private val data: FloatArray,
    private val mw: Int,
    private val mh: Int,
    private val r: Int,
    private val paintValue: Float,
) {
    private val r2 = r * r

    fun paintRow(y: Int, cy: Int, cx: Int, dxRange: IntRange) {
        if (y !in 0 until mh) return
        val dy = y - cy
        for (dx in dxRange) {
            val x = cx + dx
            if (x !in 0 until mw) continue
            if (dx * dx + dy * dy <= r2) {
                data[y * mw + x] = paintValue
            }
        }
    }
}
