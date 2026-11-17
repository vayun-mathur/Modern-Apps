package com.vayunmathur.library.image

/**
 * Requested decode size, mirrors `coil.size.Size`.
 * If both dimensions are > 0, decoder should sample/downscale to fit.
 */
data class Size(val width: Int, val height: Int) {
    constructor(size: Int) : this(size, size)

    /** Sentinel sizes for Size. */
    companion object {
        /** Sentinel meaning "original" / no size constraint. */
        val Original = Size(-1, -1)
    }

    fun isOriginal(): Boolean = width <= 0 || height <= 0
}
