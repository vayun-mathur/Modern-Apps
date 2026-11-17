package com.vayunmathur.photos.data

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.vayunmathur.photos.platform.PhotosNative

private const val OP_STRIDE = 6
private const val OP_X = 0
private const val OP_Y = 1
private const val OP_DX = 2
private const val OP_DY = 3
private const val OP_RADIUS = 4
private const val OP_STRENGTH = 5

/** Kinds of liquify tool. */
enum class LiquifyTool { Push, Twirl, Pucker, Bloat, Reconstruct }

/** Immutable value describing liquify op. */
data class LiquifyOp(
    val tool: LiquifyTool,
    val x: Float,            // normalized 0..1 center
    val y: Float,
    val dx: Float = 0f,      // normalized drag delta (for Push)
    val dy: Float = 0f,
    val radius: Float = 0.15f, // normalized
    val strength: Float = 0.5f,
)

/** Immutable value describing liquify params; carries ops. */
data class LiquifyParams(val ops: List<LiquifyOp> = emptyList()) {
    fun isIdentity(): Boolean = ops.isEmpty()
}

fun LiquifyParams.applyToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val src = IntArray(w * h)
    bitmap.getPixels(src, 0, w, 0, 0, w, h)

    val output = createBitmap(w, h)
    if (isIdentity() || w == 0 || h == 0) {
        output.setPixels(src, 0, w, 0, 0, w, h)
        return output
    }

    val tools = IntArray(ops.size) { ops[it].tool.ordinal }
    val params = FloatArray(ops.size * OP_STRIDE)
    for (i in ops.indices) {
        val op = ops[i]
        params[i * OP_STRIDE + OP_X] = op.x
        params[i * OP_STRIDE + OP_Y] = op.y
        params[i * OP_STRIDE + OP_DX] = op.dx
        params[i * OP_STRIDE + OP_DY] = op.dy
        params[i * OP_STRIDE + OP_RADIUS] = op.radius
        params[i * OP_STRIDE + OP_STRENGTH] = op.strength
    }
    val out = PhotosNative.liquify(src, w, h, tools, params)
    output.setPixels(out, 0, w, 0, 0, w, h)
    return output
}
