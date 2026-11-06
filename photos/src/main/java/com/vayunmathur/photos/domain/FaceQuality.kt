package com.vayunmathur.photos.domain

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Whether a detected face is reliable enough to *form* person clusters.
 *
 * Small, blurry, low-confidence and side-on faces give embeddings that sit between
 * identities and bridge them, so they are kept out of cluster formation. They can still
 * join an existing person later through the stricter attach step in [clusterFaces].
 * Thresholds are untuned defaults.
 */
object FaceQuality {
    /** Shortest box side, in pixels of the decoded bitmap. */
    const val MIN_FACE_SIDE_PX = 48f

    /** Detector confidence; NMS already drops anything under 0.5. */
    const val MIN_SCORE = 0.7f

    /** Nose offset from the eye midpoint, along the eye axis, as a fraction of eye distance. */
    const val MAX_YAW_RATIO = 0.35f

    /** Laplacian variance of the aligned crop's luma (0..255). Heuristic. */
    const val MIN_SHARPNESS = 20f

    /**
     * @param landmarks the five SCRFD points in pixels, interleaved, in [ARCFACE_TEMPLATE] order.
     */
    fun isClusterable(
        boxWidthPx: Float,
        boxHeightPx: Float,
        score: Float,
        landmarks: FloatArray,
        sharpness: Float,
    ): Boolean =
        min(boxWidthPx, boxHeightPx) >= MIN_FACE_SIDE_PX &&
            score >= MIN_SCORE &&
            yawRatio(landmarks) <= MAX_YAW_RATIO &&
            sharpness >= MIN_SHARPNESS

    /** 0 for a frontal face, growing as the head turns; rotation-invariant. */
    fun yawRatio(landmarks: FloatArray): Float {
        val ex = landmarks[2] - landmarks[0]
        val ey = landmarks[3] - landmarks[1]
        val eyeDistance = sqrt(ex * ex + ey * ey)
        if (eyeDistance < 1e-3f) return Float.POSITIVE_INFINITY
        val midX = (landmarks[0] + landmarks[2]) / 2f
        val midY = (landmarks[1] + landmarks[3]) / 2f
        val along = (landmarks[4] - midX) * ex + (landmarks[5] - midY) * ey
        return abs(along) / (eyeDistance * eyeDistance)
    }

    /** Variance of the 4-neighbour Laplacian over the luma of an ARGB_8888 buffer. */
    fun laplacianVariance(argb: IntArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3) return 0f
        val luma = FloatArray(width * height) { i ->
            val p = argb[i]
            0.299f * ((p shr 16) and 0xff) + 0.587f * ((p shr 8) and 0xff) + 0.114f * (p and 0xff)
        }
        var sum = 0.0
        var sumSq = 0.0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val lap = luma[i - 1] + luma[i + 1] + luma[i - width] + luma[i + width] - 4f * luma[i]
                sum += lap
                sumSq += lap * lap
            }
        }
        val count = (width - 2) * (height - 2)
        val mean = sum / count
        return (sumSq / count - mean * mean).toFloat()
    }
}
