package com.vayunmathur.photos.util

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.PointF
import android.media.FaceDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Lightweight, fully on-device face detection + matching. No cloud, no Google
 * Play Services, and no bundled ML model — it uses Android's built-in
 * [android.media.FaceDetector] to locate faces, then builds a small normalised
 * grayscale "template" of each face crop and compares templates with cosine
 * similarity.
 *
 * This is intentionally simple and easy to read rather than state-of-the-art:
 * it reliably groups the obvious cases (clear, front-facing photos of the same
 * person) which is what the optional "match faces to contacts" feature needs.
 */
object FaceRecognizer {
    /** Side length of the square grayscale template. Embedding size is [EMBED_SIDE]². */
    private const val EMBED_SIDE = 16
    private const val MAX_FACES = 8

    /**
     * Cosine-similarity threshold above which two face templates are considered
     * the same person. Higher = fewer false matches. Tunable.
     */
    const val MATCH_THRESHOLD = 0.92f

    /** Detect every face in [bitmap] and return one template per face. */
    fun detectFaces(bitmap: Bitmap): List<FloatArray> {
        // android.media.FaceDetector requires an RGB_565 bitmap with an even width.
        val evenWidth = bitmap.width and 1.inv()
        if (evenWidth < 2 || bitmap.height < 2) return emptyList()
        val prepared = if (bitmap.width == evenWidth && bitmap.config == Bitmap.Config.RGB_565) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, evenWidth, bitmap.height, true)
                .copy(Bitmap.Config.RGB_565, false)
        }

        val found = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val count = try {
            FaceDetector(prepared.width, prepared.height, MAX_FACES).findFaces(prepared, found)
        } catch (_: Throwable) {
            0
        }

        val templates = ArrayList<FloatArray>(count)
        val mid = PointF()
        for (i in 0 until count) {
            val face = found[i] ?: continue
            if (face.confidence() < 0.35f) continue
            face.getMidPoint(mid)
            val eye = face.eyesDistance()
            // Face box heuristic from eye distance: a bit wider than the eyes and
            // taller to include forehead + chin.
            val halfW = eye * 1.1f
            val halfH = eye * 1.4f
            val rect = Rect(
                (mid.x - halfW).toInt().coerceAtLeast(0),
                (mid.y - halfH * 0.9f).toInt().coerceAtLeast(0),
                (mid.x + halfW).toInt().coerceAtMost(prepared.width),
                (mid.y + halfH * 1.1f).toInt().coerceAtMost(prepared.height),
            )
            if (rect.width() < 8 || rect.height() < 8) continue
            templates += embedCrop(prepared, rect)
        }
        return templates
    }

    /** Build a normalised grayscale template from a face region of [bitmap]. */
    private fun embedCrop(bitmap: Bitmap, rect: Rect): FloatArray {
        val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        val small = Bitmap.createScaledBitmap(crop, EMBED_SIDE, EMBED_SIDE, true)
        val px = IntArray(EMBED_SIDE * EMBED_SIDE)
        small.getPixels(px, 0, EMBED_SIDE, 0, 0, EMBED_SIDE, EMBED_SIDE)

        val gray = FloatArray(px.size)
        var mean = 0f
        for (i in px.indices) {
            val c = px[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val v = 0.299f * r + 0.587f * g + 0.114f * b
            gray[i] = v
            mean += v
        }
        mean /= gray.size

        // Subtract the mean (lighting invariance) then L2-normalise so cosine
        // similarity ignores overall brightness/contrast.
        var norm = 0f
        for (i in gray.indices) {
            gray[i] -= mean
            norm += gray[i] * gray[i]
        }
        norm = sqrt(norm)
        if (norm > 1e-6f) {
            for (i in gray.indices) gray[i] /= norm
        }
        return gray
    }

    /** Cosine similarity of two L2-normalised templates (range roughly -1..1). */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    /**
     * Return the [contactKey] whose template best matches [face] above
     * [MATCH_THRESHOLD], or null if none is close enough.
     */
    fun bestMatch(face: FloatArray, contacts: List<Pair<String, FloatArray>>): String? {
        var bestKey: String? = null
        var bestSim = MATCH_THRESHOLD
        for ((key, template) in contacts) {
            val sim = similarity(face, template)
            if (sim >= bestSim) {
                bestSim = sim
                bestKey = key
            }
        }
        return bestKey
    }

    fun floatsToBytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buffer.putFloat(v)
        return buffer.array()
    }

    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) out[i] = buffer.float
        return out
    }
}
