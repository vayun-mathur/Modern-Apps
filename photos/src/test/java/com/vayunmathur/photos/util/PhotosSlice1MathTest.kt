package com.vayunmathur.photos.util

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-math contract tests for slice 1/3 (`data/workers`): the vector helpers on
 * [FaceRecognizer] and [ClipEmbedder] that every clustering/search path depends on.
 *
 * No Android framework, no models, no database — plain JVM, so the merged
 * coverage report credits every instruction here.
 */
class PhotosSlice1MathTest {

    // ---- FaceRecognizer.similarity ----

    @Test
    fun faceSimilarityIdenticalVectorsIsOne() {
        val a = floatArrayOf(0.6f, 0.8f, 0f)
        assertEquals(1f, FaceRecognizer.similarity(a, a), 1e-6f)
    }

    @Test
    fun faceSimilarityOrthogonalVectorsIsZero() {
        assertEquals(
            0f,
            FaceRecognizer.similarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
            1e-6f,
        )
    }

    @Test
    fun faceSimilarityOppositeVectorsIsMinusOne() {
        assertEquals(
            -1f,
            FaceRecognizer.similarity(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)),
            1e-6f,
        )
    }

    @Test
    fun faceSimilarityIgnoresMagnitude() {
        val a = floatArrayOf(3f, 4f)
        val b = floatArrayOf(6f, 8f)
        assertEquals(1f, FaceRecognizer.similarity(a, b), 1e-6f)
    }

    @Test
    fun faceSimilarityRejectsBadInputs() {
        assertEquals(-1f, FaceRecognizer.similarity(floatArrayOf(1f, 2f), floatArrayOf(1f)))
        assertEquals(-1f, FaceRecognizer.similarity(floatArrayOf(), floatArrayOf()))
        assertEquals(
            -1f,
            FaceRecognizer.similarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)),
        )
    }

    // ---- FaceRecognizer.l2Normalize ----

    @Test
    fun faceNormalizeProducesUnitVector() {
        val out = FaceRecognizer.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(1f, sqrt(out[0] * out[0] + out[1] * out[1]), 1e-6f)
        assertEquals(0.6f, out[0], 1e-6f)
        assertEquals(0.8f, out[1], 1e-6f)
    }

    @Test
    fun faceNormalizeZeroVectorReturnsSameInstance() {
        val zero = FloatArray(4)
        assertTrue(FaceRecognizer.l2Normalize(zero) === zero)
    }

    // ---- FaceRecognizer floats<->bytes ----

    @Test
    fun faceFloatsRoundTripThroughBytes() {
        val values = floatArrayOf(1f, -2.5f, 0f, 3.75f, Float.MAX_VALUE)
        assertContentEquals(values, FaceRecognizer.bytesToFloats(FaceRecognizer.floatsToBytes(values)))
    }

    @Test
    fun faceBytesToFloatsTruncatesPartialFloat() {
        // 6 bytes hold one full float plus two stray bytes; the stray bytes are dropped.
        assertEquals(1, FaceRecognizer.bytesToFloats(ByteArray(6)).size)
        assertEquals(0, FaceRecognizer.bytesToFloats(ByteArray(0)).size)
    }

    // ---- ClipEmbedder.cosine ----

    @Test
    fun clipCosineIdenticalVectorsIsOne() {
        val a = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        assertEquals(1f, ClipEmbedder.cosine(a, a), 1e-6f)
    }

    @Test
    fun clipCosineOrthogonalIsZero() {
        assertEquals(
            0f,
            ClipEmbedder.cosine(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f)),
            1e-6f,
        )
    }

    @Test
    fun clipCosineRejectsBadInputs() {
        assertEquals(-1f, ClipEmbedder.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)))
        assertEquals(-1f, ClipEmbedder.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)))
    }

    // ---- ClipEmbedder.l2Normalize + floats<->bytes ----

    @Test
    fun clipNormalizeProducesUnitVector() {
        val out = ClipEmbedder.l2Normalize(floatArrayOf(1f, 2f, 3f))
        var norm = 0f
        for (v in out) norm += v * v
        assertEquals(1f, sqrt(norm), 1e-6f)
    }

    @Test
    fun clipFloatsRoundTripThroughBytes() {
        val values = FloatArray(512) { it * 0.001f - 0.25f }
        assertContentEquals(values, ClipEmbedder.bytesToFloats(ClipEmbedder.floatsToBytes(values)))
    }

    // ---- Version/model constants ----

    @Test
    fun embedderVersionsArePositive() {
        assertTrue(FaceRecognizer.EMBEDDER_VERSION > 0)
        assertTrue(ClipEmbedder.EMBEDDER_VERSION > 0)
        assertTrue(FaceRecognizer.INPUT_SIZE > 0)
    }

    @Test
    fun clipModelIdIsPinned() {
        assertEquals(
            "onnx-community/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M-ONNX",
            ClipEmbedder.MODEL_ID,
        )
    }
}
