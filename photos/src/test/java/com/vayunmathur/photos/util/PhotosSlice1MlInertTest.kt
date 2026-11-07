package com.vayunmathur.photos.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 1/3 second pass: [FaceRecognizer]/[ClipEmbedder] inert behaviour when
 * the ML native library cannot come up on this host (no Vulkan, incomplete
 * JNI surface). Pins the documented "unavailable, never crash" contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice1MlInertTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun faceModelsReportUnavailableAndDetectNothing() {
        assertFalse(FaceRecognizer.modelsAvailable(context))
        // Latched failure: asking again does not re-attempt the native load.
        assertFalse(FaceRecognizer.modelsAvailable(context))
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        assertEquals(emptyList(), FaceRecognizer.detectAndEmbed(context, bitmap))
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun detectedFaceValueSemantics() {
        val face = FaceRecognizer.DetectedFace(
            embedding = floatArrayOf(0.5f, 0.5f),
            left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.8f,
            clusterable = true,
        )
        assertEquals(0.1f, face.left)
        assertEquals(0.8f, face.bottom)
        assertTrue(face.clusterable)
        assertTrue(floatArrayOf(0.5f, 0.5f).contentEquals(face.embedding))
    }

    @Test
    fun clipSupportIsUnavailableAndInfoIsPinned() {
        assertEquals(ClipEmbedder.Support.UNAVAILABLE, ClipEmbedder.embeddingSupport(context))
        val info = ClipEmbedder.embeddingInfo(context)
        assertEquals(info.modelId, ClipEmbedder.embeddingInfo(context).modelId)
        assertTrue(info.dim > 0)
        assertEquals(ClipEmbedder.MODEL_ID, info.modelId)
        assertEquals(512, info.dim)
    }

    @Test
    fun clipEmbeddingsFailClosedWhenUnavailable() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> {
            ClipEmbedder.textEmbedding(context, "a red car")
        }
        assertFailsWith<IllegalStateException> {
            ClipEmbedder.imageEmbedding(context, Uri.parse("content://media/1"))
        }
        // close() is idempotent and resets the init latch without crashing.
        ClipEmbedder.close()
        ClipEmbedder.close()
        assertEquals(ClipEmbedder.Support.UNAVAILABLE, ClipEmbedder.embeddingSupport(context))
    }

    @Test
    fun clipInfoModelAndDimensionAreStable() {
        val info = ClipEmbedder.Info("m", 384)
        assertEquals(ClipEmbedder.Info("m", 384), info)
        assertFalse(info == info.copy(dim = 128))
    }
}
