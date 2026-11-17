package com.vayunmathur.pdf.platform

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hand-builds the little-endian primitive buffer that `pdf/src/main/rust/src/wire.rs`
 * emits, so [SafePdfParser] can be decoded against bytes written independently of it.
 *
 * Field order here is transcribed from `wire::serialize` (wire.rs:131-308), NOT from the
 * doc comment on [SafePdfParser] — the point is to catch the two drifting apart.
 */
class WireWriter(
    private val version: Int = SafePdfParser.WIRE_VERSION,
    private val width: Float = 612f,
    private val height: Float = 792f,
    /** Emit a legacy v1 header (bare `f32 w, f32 h, u32 count`, no magic). */
    private val legacyV1: Boolean = false,
) {
    private val body = ByteArrayOutputStream()
    private var count = 0

    /** Overrides the header's primitive count, to exercise the clamp and truncation paths. */
    var declaredCount: Int? = null

    private fun u8(v: Int) = apply { body.write(v and 0xFF) }

    private fun bytes(b: ByteArray) = apply { body.write(b) }

    private fun u16(v: Int) = bytes(le(2) { putShort((v and 0xFFFF).toShort()) })

    private fun i32(v: Int) = bytes(le(4) { putInt(v) })

    private fun f32(v: Float) = bytes(le(4) { putFloat(v) })

    private fun le(n: Int, block: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply(block).array()

    private fun points(pts: List<Pair<Float, Float>>) = apply {
        u16(pts.size)
        for ((x, y) in pts) { f32(x); f32(y) }
    }

    private fun prim(tag: Int) = apply { count++; u8(tag) }

    fun text(
        x: Float = 0f,
        y: Float = 0f,
        size: Float = 12f,
        argb: Int = 0xFF000000.toInt(),
        text: String = "A",
        strokeArgb: Int? = null,
        strokeWidth: Float = 0f,
        renderMode: Int = 0,
        blend: Int = 0,
        advance: Float = 6f,
        bold: Boolean = false,
        italic: Boolean = false,
        family: Int = 0,
        outline: Boolean = false,
        hScale: Float = 1f,
    ) = prim(1).apply {
        f32(x); f32(y); f32(size); i32(argb)
        val utf8 = text.toByteArray(Charsets.UTF_8)
        u16(utf8.size); bytes(utf8)
        if (strokeArgb != null) { u8(1); i32(strokeArgb); f32(strokeWidth) } else { u8(0); i32(0); f32(0f) }
        if (version >= 4) u8(renderMode)
        if (version >= 5) u8(blend)
        if (version >= 7) f32(advance)
        if (version >= 8) {
            var flags = 0
            if (bold) flags = flags or 1
            if (italic) flags = flags or 2
            flags = flags or ((family and 0x3) shl 2)
            if (outline) flags = flags or 0x10
            u8(flags)
            f32(hScale)
        }
    }

    fun fill(
        argb: Int = 0xFF112233.toInt(),
        evenOdd: Boolean = false,
        contours: List<List<Pair<Float, Float>>> = listOf(square()),
        blend: Int = 0,
    ) = prim(2).apply {
        i32(argb); u8(if (evenOdd) 1 else 0)
        if (version >= 6) {
            u16(contours.size)
            contours.forEach { points(it) }
        } else {
            points(contours.first())
        }
        if (version >= 5) u8(blend)
    }

    fun stroke(
        argb: Int = 0xFF445566.toInt(),
        width: Float = 2f,
        dash: FloatArray = FloatArray(0),
        dashPhase: Float = 0f,
        cap: Int = 0,
        join: Int = 0,
        miter: Float = 10f,
        pts: List<Pair<Float, Float>> = listOf(0f to 0f, 10f to 10f),
        blend: Int = 0,
    ) = prim(3).apply {
        i32(argb); f32(width)
        u8(dash.size); dash.forEach { f32(it) }
        f32(dashPhase)
        if (version >= 2) { u8(cap); u8(join); f32(miter) }
        points(pts)
        if (version >= 5) u8(blend)
    }

    fun image(
        ctm: FloatArray = floatArrayOf(100f, 0f, 0f, 100f, 10f, 20f),
        w: Int = 2,
        h: Int = 2,
        format: Int = 0,
        alpha: Float = 1f,
        blend: Int = 0,
        interpolate: Boolean = true,
        data: ByteArray = ByteArray(2 * 2 * 4),
    ) = prim(4).apply {
        ctm.forEach { f32(it) }
        i32(w); i32(h); u8(format)
        if (version >= 9) f32(alpha)
        if (version >= 10) u8(blend)
        if (version >= 11) u8(if (interpolate) 1 else 0)
        i32(data.size); bytes(data)
    }

    fun imageTiled(
        ctm: FloatArray = floatArrayOf(8f, 0f, 0f, 8f, 0f, 0f),
        w: Int = 2,
        h: Int = 2,
        xstep: Float = 8f,
        ystep: Float = 8f,
        i0: Int = 0,
        j0: Int = 0,
        nx: Int = 4,
        ny: Int = 4,
        alpha: Float = 1f,
        blend: Int = 0,
        data: ByteArray = ByteArray(2 * 2 * 4),
    ) = prim(14).apply {
        ctm.forEach { f32(it) }
        i32(w); i32(h); f32(xstep); f32(ystep)
        i32(i0); i32(j0); i32(nx); i32(ny)
        f32(alpha); u8(blend)
        i32(data.size); bytes(data)
    }

    fun clipPush(
        evenOdd: Boolean = false,
        pts: List<Pair<Float, Float>> = square(),
        pathOps: List<PathOp>? = null,
    ) = prim(5).apply {
        u8(if (evenOdd) 1 else 0)
        points(pts)
        if (version >= 4) {
            val ops = pathOps ?: emptyList()
            u16(ops.size)
            for (op in ops) when (op) {
                is PathOp.Move -> { u8(0); f32(op.x); f32(op.y) }
                is PathOp.Line -> { u8(1); f32(op.x); f32(op.y) }
                is PathOp.Cubic -> {
                    u8(2)
                    f32(op.x1); f32(op.y1); f32(op.x2); f32(op.y2); f32(op.x3); f32(op.y3)
                }
                PathOp.Close -> u8(3)
            }
        }
    }

    fun clipPop() = prim(6)

    fun groupPush(isolated: Boolean = true, knockout: Boolean = false, alpha: Float = 1f, blend: Int = 0) =
        prim(7).apply { u8(if (isolated) 1 else 0); u8(if (knockout) 1 else 0); f32(alpha); u8(blend) }

    fun groupPop() = prim(8)

    fun textClipApply() = prim(9)

    fun softMaskPush(maskType: Int = 0) = prim(10).apply { u8(maskType) }

    fun softMaskContent() = prim(11)

    fun softMaskPop() = prim(12)

    fun softMaskTransfer(lut: ByteArray) = prim(13).apply {
        require(lut.size == 256) { "the /TR LUT is exactly 256 samples on the wire" }
        bytes(lut)
    }

    /** An unknown tag, to exercise the desync guard. */
    fun rawTag(tag: Int) = prim(tag)

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        if (legacyV1) {
            out.write(le(4) { putFloat(width) })
        } else {
            out.write(le(4) { putInt(SafePdfParser.WIRE_MAGIC) })
            out.write(le(4) { putInt(version) })
            out.write(le(4) { putFloat(width) })
        }
        out.write(le(4) { putFloat(height) })
        out.write(le(4) { putInt(declaredCount ?: count) })
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    companion object {
        fun square(): List<Pair<Float, Float>> =
            listOf(0f to 0f, 10f to 0f, 10f to 10f, 0f to 10f)
    }
}
