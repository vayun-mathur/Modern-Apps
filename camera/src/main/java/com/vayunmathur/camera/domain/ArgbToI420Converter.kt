package com.vayunmathur.camera.domain

import java.nio.ByteBuffer

/**
 * One destination plane of a YUV 4:2:0 image: the backing [buffer] plus its strides, i.e. the
 * fields of `android.media.Image.Plane` without the Android type, so the conversion stays pure
 * JVM and unit-testable. Row `r`, sample `i` lives at `r * rowStride + i * pixelStride`.
 */
class YuvPlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

/**
 * Converts ARGB_8888 pixels (as returned by `Bitmap.getPixels`) to BT.601 limited-range
 * YUV 4:2:0, writing into caller-provided planes of any layout the encoder hands out: fully
 * planar I420 (pixelStride 1) or semi-planar NV12/NV21, whose U and V planes alias one buffer
 * with pixelStride 2.
 *
 * Each source row is converted into a scratch row first and then written with one bulk
 * `ByteBuffer.put` per plane row, instead of one indexed `put` per sample (the old Motion Photo
 * path did ~1.8M absolute puts on a direct buffer per 1280x960 frame). For pixelStride > 1 the
 * destination row span is bulk-read, patched and bulk-written back, so the interleaved bytes
 * belonging to the other chroma plane survive and the U/V write order does not matter.
 *
 * Chroma takes the top-left pixel of each 2x2 block, and the fixed-point weights match the
 * previous per-pixel encoder exactly, so the encoded clip is bit-identical to before.
 * Instances hold per-size scratch buffers: reuse one per encode, not across threads.
 */
class ArgbToI420Converter(private val width: Int, private val height: Int) {

    init {
        require(width > 0 && height > 0) { "Invalid frame size ${width}x$height" }
    }

    private val chromaWidth = (width + 1) / 2
    private val yRow = ByteArray(width)
    private val uRow = ByteArray(chromaWidth)
    private val vRow = ByteArray(chromaWidth)
    private var strided = ByteArray(0)

    /** Converts [argb] (row-major, stride == width) into the [y], [u] and [v] planes. */
    fun convert(argb: IntArray, y: YuvPlane, u: YuvPlane, v: YuvPlane) {
        require(argb.size >= width * height) { "Need ${width * height} pixels, got ${argb.size}" }
        for (row in 0 until height) {
            val withChroma = row and 1 == 0
            convertRow(argb, row * width, withChroma)
            writeRow(y, row, yRow, width)
            if (withChroma) {
                writeRow(u, row shr 1, uRow, chromaWidth)
                writeRow(v, row shr 1, vRow, chromaWidth)
            }
        }
    }

    /** Fills [yRow] (and, on even rows, [uRow]/[vRow]) from one source row starting at [base]. */
    private fun convertRow(argb: IntArray, base: Int, withChroma: Boolean) {
        for (x in 0 until width) {
            val c = argb[base + x]
            val r = (c shr RED_SHIFT) and CHANNEL_MASK
            val g = (c shr GREEN_SHIFT) and CHANNEL_MASK
            val b = c and CHANNEL_MASK
            yRow[x] = clamp(((LUMA_R * r + LUMA_G * g + LUMA_B * b + ROUND) shr SHIFT) + LUMA_OFFSET)
            if (withChroma && x and 1 == 0) {
                val cx = x shr 1
                uRow[cx] = clamp(((U_R * r + U_G * g + U_B * b + ROUND) shr SHIFT) + CHROMA_OFFSET)
                vRow[cx] = clamp(((V_R * r + V_G * g + V_B * b + ROUND) shr SHIFT) + CHROMA_OFFSET)
            }
        }
    }

    /** Writes [count] samples of [src] into plane row [rowIndex], honouring both strides. */
    private fun writeRow(plane: YuvPlane, rowIndex: Int, src: ByteArray, count: Int) {
        // duplicate(): position changes stay local; the plane's own position/limit are untouched.
        val buf = plane.buffer.duplicate()
        val start = rowIndex * plane.rowStride
        if (plane.pixelStride == 1) {
            buf.position(start)
            buf.put(src, 0, count)
            return
        }
        // Span ends on the last sample, not on a full stride: the final row of an Image plane
        // is not padded, so reading (count * pixelStride) bytes would overrun the buffer.
        val span = (count - 1) * plane.pixelStride + 1
        if (strided.size < span) strided = ByteArray(span)
        buf.position(start)
        buf.get(strided, 0, span)
        for (i in 0 until count) strided[i * plane.pixelStride] = src[i]
        buf.position(start)
        buf.put(strided, 0, span)
    }

    private companion object {
        const val CHANNEL_MASK = 0xFF
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8

        // BT.601 limited-range fixed-point (x256) weights, rounding bias and offsets.
        const val LUMA_R = 66
        const val LUMA_G = 129
        const val LUMA_B = 25
        const val U_R = -38
        const val U_G = -74
        const val U_B = 112
        const val V_R = 112
        const val V_G = -94
        const val V_B = -18
        const val ROUND = 128
        const val SHIFT = 8
        const val LUMA_OFFSET = 16
        const val CHROMA_OFFSET = 128
        const val SAMPLE_MAX = 255

        fun clamp(value: Int): Byte = value.coerceIn(0, SAMPLE_MAX).toByte()
    }
}
