package com.vayunmathur.library.ml

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Segmenters, face detector/embedder and the OCR handle without their model
 * assets: unavailable, inert, and safe to close. Plus the pure data types.
 */
@RunWith(RobolectricTestRunner::class)
class SegmentationAndFacesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun bitmap(w: Int = 8, h: Int = 6): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun `all three segmenters are unavailable without assets`() {
        val selfie = SelfieSegmenter(context)
        assertFalse(selfie.isAvailable)
        assertNull(selfie.segment(bitmap()))
        selfie.close(); selfie.close()

        val modnet = ModnetSegmenter(context)
        assertFalse(modnet.isAvailable)
        assertNull(modnet.segment(bitmap()))
        modnet.close()

        val subject = SubjectSegmenter(context)
        assertFalse(subject.isAvailable)
        assertNull(subject.segment(bitmap()))
        assertNull(subject.segment(bitmap(1, 1)))
        subject.close()

        assertEquals("selfie_segmentation.maml", SelfieSegmenter.DEFAULT_ASSET)
        assertEquals("modnet.maml", ModnetSegmenter.DEFAULT_ASSET)
        assertEquals("u2netp.maml", SubjectSegmenter.DEFAULT_ASSET)
    }

    @Test
    fun `segmenters with a bogus asset name are still unavailable`() {
        val selfie = SelfieSegmenter(context, "no/such/selfie.maml")
        assertFalse(selfie.isAvailable)
        assertNull(selfie.segment(bitmap()))
        selfie.close()
    }

    @Test
    fun `segmentation mask holds its dimensions and values`() {
        val mask = SegmentationMask(2, 2, floatArrayOf(0f, 0.25f, 0.75f, 1f))
        assertEquals(2, mask.width)
        assertEquals(2, mask.height)
        assertEquals(listOf(0f, 0.25f, 0.75f, 1f), mask.mask.toList())
    }

    @Test
    fun `face detector without asset finds nothing`() {
        val detector = FaceDetector(context)
        assertFalse(detector.isAvailable)
        assertEquals(emptyList(), detector.detect(bitmap()))
        assertEquals(emptyList(), detector.detect(bitmap(32, 32)))
        detector.close(); detector.close()
        assertEquals("scrfd_500m.maml", FaceDetector.DEFAULT_ASSET)
    }

    @Test
    fun `face embedder without asset returns null`() {
        val embedder = FaceEmbedder(context)
        assertFalse(embedder.isAvailable)
        assertNull(embedder.embed(bitmap(112, 112)))
        embedder.close()
        assertEquals(512, FaceEmbedder.EMBEDDING_LENGTH)
        assertEquals("w600k_mbf.maml", FaceEmbedder.DEFAULT_ASSET)
    }

    @Test
    fun `detected face box bounds come from its own fields`() {
        val face = DetectedFaceBox(
            left = 0.1f, top = 0.2f, right = 0.5f, bottom = 0.6f,
            leftEyeX = 0.2f, leftEyeY = 0.3f, rightEyeX = 0.4f, rightEyeY = 0.3f,
            score = 0.9f, noseX = 0.3f, noseY = 0.4f,
            mouthLeftX = 0.22f, mouthLeftY = 0.5f, mouthRightX = 0.38f, mouthRightY = 0.5f,
        )
        assertEquals(face, face.copy())
        assertEquals(0.9f, face.score)
        assertTrue(face.right > face.left && face.bottom > face.top)
    }

    @Test
    fun `text recognizer without assets returns no lines`() {
        val recognizer = TextRecognizer(context)
        assertFalse(recognizer.isAvailable)
        assertEquals(emptyList(), recognizer.recognize(bitmap()))
        assertEquals(emptyList(), recognizer.recognize(bitmap(64, 20)))
        recognizer.close(); recognizer.close()
    }

    @Test
    fun `recognized line bounds are the corners extremes`() {
        val line = RecognizedLine(
            text = "hello",
            confidence = 0.75f,
            corners = listOf(10f to 20f, 110f to 15f, 112f to 40f, 8f to 45f),
            vertical = false,
        )
        assertEquals(8f, line.left)
        assertEquals(15f, line.top)
        assertEquals(112f, line.right)
        assertEquals(45f, line.bottom)
        assertEquals(line, line.copy(vertical = true).copy(vertical = false))
        val vertical = line.copy(vertical = true)
        assertTrue(vertical.vertical)
    }
}
