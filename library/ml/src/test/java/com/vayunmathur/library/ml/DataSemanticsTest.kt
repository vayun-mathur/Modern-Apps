package com.vayunmathur.library.ml

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Data-class semantics and tower companion paths that stop at an unreadable file. */
@RunWith(RobolectricTestRunner::class)
class DataSemanticsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `detected face box equality hash and copy follow every field`() {
        fun face(score: Float) = DetectedFaceBox(
            0.1f, 0.2f, 0.5f, 0.6f, 0.2f, 0.3f, 0.4f, 0.3f,
            score, 0.3f, 0.4f, 0.22f, 0.5f, 0.38f, 0.5f,
        )
        assertEquals(face(0.9f), face(0.9f))
        assertEquals(face(0.9f).hashCode(), face(0.9f).hashCode())
        assertNotEquals(face(0.9f), face(0.5f))
        assertNotEquals<Any>(face(0.9f), "not a face")
        assertEquals(0.5f, face(0.9f).copy(score = 0.5f).score)
        assertTrue(face(0.9f).toString().contains("0.9"))
    }

    @Test
    fun `recognized line equality and copy follow fields`() {
        val line = RecognizedLine("abc", 0.5f, listOf(0f to 0f, 1f to 1f), vertical = false)
        assertEquals(line, line.copy())
        assertNotEquals(line, line.copy(text = "abd"))
        assertNotEquals(line, line.copy(confidence = 0.6f))
        assertNotEquals(line, line.copy(vertical = true))
        assertEquals(line.hashCode(), line.copy().hashCode())
    }

    @Test
    fun `gemma image and audio parts compare by content not reference`() {
        val imageA = Gemma4Handle.Part.Image(floatArrayOf(1f, 2f))
        val imageB = Gemma4Handle.Part.Image(floatArrayOf(1f, 2f))
        val imageC = Gemma4Handle.Part.Image(floatArrayOf(1f, 3f))
        assertEquals(imageA, imageB)
        assertEquals(imageA.hashCode(), imageB.hashCode())
        assertNotEquals(imageA, imageC)
        assertNotEquals<Any>(imageA, Gemma4Handle.Part.Audio(floatArrayOf(1f, 2f)))
        assertEquals(
            Gemma4Handle.Part.Text("x"),
            Gemma4Handle.Part.Text("x"),
        )
        assertNotEquals(Gemma4Handle.Part.Text("x"), Gemma4Handle.Part.Text("y"))
    }

    @Test
    fun `gemma towers with a present but unreadable model file stay unavailable`() {
        val audioDir = java.nio.file.Files.createTempDirectory("gemma-audio-file").toFile()
        File(audioDir, Gemma4AudioHandle.AUDIO).writeBytes(byteArrayOf(1, 2, 3, 4))
        val audio = Gemma4AudioHandle.inDirectory(audioDir)
        assertFalse(audio.isAvailable)
        assertNull(audio.encode(FloatArray(Gemma4AudioHandle.MIN_SAMPLES + 1)))
        audio.close()

        val visionDir = java.nio.file.Files.createTempDirectory("gemma-vision-file").toFile()
        File(visionDir, Gemma4VisionHandle.VISION).writeBytes(byteArrayOf(5, 6, 7))
        val vision = Gemma4VisionHandle.inDirectory(visionDir)
        assertFalse(vision.isAvailable)
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        assertNull(vision.encode(bitmap))
        vision.close()
        bitmap.recycle()
    }

    @Test
    fun `sampling presets have their documented shapes`() {
        assertEquals(Sampling.Greedy, Sampling(topK = 1, topP = 1f, temperature = 0f, seed = Sampling.Greedy.seed))
        assertTrue(Sampling.Chat.topK > 1)
        assertTrue(Sampling.Chat.topP in 0f..1f)
        val custom = Sampling(topK = 8, topP = 0.5f, temperature = 1.2f, seed = 7L)
        assertEquals(custom, custom.copy())
        assertNotEquals(custom, custom.copy(seed = 8L))
    }
}
