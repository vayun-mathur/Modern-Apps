package com.vayunmathur.photos.domain

/** Side of the square crop the face embedder takes. */
const val ALIGNED_FACE_SIZE = 112

/**
 * ArcFace's five-point template in a [ALIGNED_FACE_SIZE] crop, interleaved `x, y`:
 * left eye, right eye, nose, left mouth corner, right mouth corner. The embedder was
 * trained on crops warped onto exactly these points (insightface `norm_crop`).
 */
val ARCFACE_TEMPLATE: FloatArray = floatArrayOf(
    38.2946f, 51.6963f,
    73.5318f, 51.5014f,
    56.0252f, 71.7366f,
    41.5493f, 92.3655f,
    70.7299f, 92.2041f,
)

/**
 * Least-squares similarity transform (rotation, uniform scale, translation; no
 * reflection) taking the interleaved points [src] onto [dst].
 *
 * Returns `[a, b, tx, c, d, ty]` with `x' = a·x + b·y + tx`, `y' = c·x + d·y + ty`, the
 * order `android.graphics.Matrix.setValues` reads, or null when [src] has no spread.
 */
fun similarityTransform(src: FloatArray, dst: FloatArray): FloatArray? {
    require(src.size == dst.size && src.size >= 4 && src.size % 2 == 0)
    val n = src.size / 2
    var sx = 0f; var sy = 0f; var dx = 0f; var dy = 0f
    for (i in 0 until n) {
        sx += src[2 * i]; sy += src[2 * i + 1]
        dx += dst[2 * i]; dy += dst[2 * i + 1]
    }
    sx /= n; sy /= n; dx /= n; dy /= n

    var dot = 0f; var cross = 0f; var spread = 0f
    for (i in 0 until n) {
        val px = src[2 * i] - sx
        val py = src[2 * i + 1] - sy
        val qx = dst[2 * i] - dx
        val qy = dst[2 * i + 1] - dy
        dot += px * qx + py * qy
        cross += px * qy - py * qx
        spread += px * px + py * py
    }
    if (spread < 1e-6f) return null
    val a = dot / spread
    val c = cross / spread
    return floatArrayOf(
        a, -c, dx - (a * sx - c * sy),
        c, a, dy - (c * sx + a * sy),
    )
}
