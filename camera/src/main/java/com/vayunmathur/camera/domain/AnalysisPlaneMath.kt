package com.vayunmathur.camera.domain

import java.nio.ByteBuffer

/**
 * A non-owning view of one image plane: the (usually direct) [buffer] plus the
 * strides needed to address pixels in it. No copy is made to create or read a
 * view — that is the whole point of the Camera2 analysis path: the CameraX
 * path copies plane 0 into a fresh [ByteArray] per frame, and `toBitmap()`
 * converts the whole frame, before any math runs.
 *
 * Lifetime: the view is only valid while the owning frame is open. Consumers
 * must finish reading before the frame is closed and must never retain the
 * buffer beyond that call.
 */
class PlaneView(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
    val width: Int,
    val height: Int,
) {
    /** Byte offset of pixel ([x], [y]) inside [buffer]. */
    fun offsetOf(x: Int, y: Int): Int = y * rowStride + x * pixelStride
}

/**
 * Luma statistics over a Y plane: a strided [mean] plus a downsampled grid.
 *
 * @property mean mean of the strided luma samples (see [LumaPlaneMath]), on
 *   the 0–255 scale. Computed from an integer sum, so the Kotlin and native
 *   implementations agree exactly before the final division.
 * @property grid row-major downsampled luma grid, [gridWidth] samples per row.
 */
class LumaStats(
    val mean: Double,
    val grid: FloatArray,
    val gridWidth: Int,
    val gridHeight: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LumaStats) return false
        return mean == other.mean && gridWidth == other.gridWidth &&
            gridHeight == other.gridHeight && grid.contentEquals(other.grid)
    }

    override fun hashCode(): Int {
        var result = mean.hashCode()
        result = 31 * result + grid.contentHashCode()
        result = 31 * result + gridWidth
        result = 31 * result + gridHeight
        return result
    }
}

/**
 * The one definition of the cheap Y-plane math, shared by the CameraX
 * analyzers, the Camera2 [PlaneView] consumers, the Kotlin fallback and (by
 * contract, proven by tests) the Rust native implementation.
 *
 * Mean: samples are taken every [LUMA_SAMPLE_STEP] pixels in raster order
 * (pixel index `n = y * width + x`, `n % 16 == 0`), each read through the
 * plane strides, so row padding is never mistaken for pixels.
 *
 * Grid: at most [GRID_MAX_SIDE] samples per side, taking every
 * `strideX`/`strideY`-th pixel, exactly as the video motion diff has always
 * sampled plane 0.
 */
object LumaPlaneMath {
    const val LUMA_SAMPLE_STEP: Int = 16
    const val GRID_MAX_SIDE: Int = 96

    /** Strided mean luma, or null when the plane cannot be read. */
    fun mean(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
    ): Double? = stats(buffer, rowStride, pixelStride, width, height)?.mean

    fun mean(plane: PlaneView): Double? =
        mean(plane.buffer, plane.rowStride, plane.pixelStride, plane.width, plane.height)

    /**
     * Downsampled luma grid and its width, or null when unreadable. The
     * buffer is read by absolute index, so the caller's buffer position is
     * untouched; a duplicate is not required.
     */
    fun grid(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
    ): Pair<FloatArray, Int>? {
        val stats = gridOnly(buffer, rowStride, pixelStride, width, height) ?: return null
        return stats.first to stats.second
    }

    fun grid(plane: PlaneView): Pair<FloatArray, Int>? =
        grid(plane.buffer, plane.rowStride, plane.pixelStride, plane.width, plane.height)

    /** Both statistics in one contract; the grid is computed as in [grid]. */
    fun stats(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
    ): LumaStats? {
        if (!isReadable(buffer, rowStride, pixelStride, width, height)) return null
        val (grid, gridWidth, gridHeight) =
            gridOnly(buffer, rowStride, pixelStride, width, height) ?: return null
        var sum = 0L
        var count = 0L
        var n = 0L
        val total = width.toLong() * height.toLong()
        while (n < total) {
            val x = (n % width).toInt()
            val y = (n / width).toInt()
            sum += (buffer.get(y * rowStride + x * pixelStride).toInt() and 0xFF).toLong()
            count++
            n += LUMA_SAMPLE_STEP
        }
        if (count == 0L) return null
        return LumaStats(sum.toDouble() / count.toDouble(), grid, gridWidth, gridHeight)
    }

    fun stats(plane: PlaneView): LumaStats? =
        stats(plane.buffer, plane.rowStride, plane.pixelStride, plane.width, plane.height)

    private fun gridOnly(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
    ): Triple<FloatArray, Int, Int>? {
        if (!isReadable(buffer, rowStride, pixelStride, width, height)) return null
        val strideX = maxOf(1, width / GRID_MAX_SIDE)
        val strideY = maxOf(1, height / GRID_MAX_SIDE)
        val gridWidth = (width + strideX - 1) / strideX
        val gridHeight = (height + strideY - 1) / strideY
        val grid = FloatArray(gridWidth * gridHeight)
        var out = 0
        var y = 0
        while (y < height) {
            val rowStart = y * rowStride
            var x = 0
            while (x < width) {
                grid[out++] = (buffer.get(rowStart + x * pixelStride).toInt() and 0xFF).toFloat()
                x += strideX
            }
            y += strideY
        }
        return Triple(grid, gridWidth, gridHeight)
    }

    private fun isReadable(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
    ): Boolean {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return false
        val last = (height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride
        return last < buffer.limit()
    }
}
