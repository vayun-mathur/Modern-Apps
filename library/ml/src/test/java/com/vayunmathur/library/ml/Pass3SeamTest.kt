package com.vayunmathur.library.ml

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3 (library/ml): the segmenter/detector native-call seams and the
 * Gemma4 file-check gauntlet, driven on the host with fakes — the JNI
 * bridge itself has no host symbols, so everything past these seams is
 * asserted through their contracts.
 */
@RunWith(RobolectricTestRunner::class)
class Pass3SeamTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `segmenter runs its full lifecycle through the seams`() {
        var destroyedWith: Long? = null
        var createdWith: ByteArray? = null
        val segmenter = NativeSegmenter(
            context = context,
            assetName = "fake.maml",
            tag = "test",
            create = { bytes -> createdWith = bytes; 7L },
            readAsset = { byteArrayOf(4, 5, 6) },
            segmentNative = { _, pixels, w, h ->
                // a 2x2 mask over whatever pixels arrived
                assertEquals(w * h, pixels.size)
                floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
            },
            maskWidthNative = { 2 },
            maskHeightNative = { 2 },
            destroyNative = { destroyedWith = it },
        )
        assertTrue(segmenter.isAvailable)
        assertEquals(byteArrayOf(4, 5, 6).toList(), createdWith?.toList())
        val bmp = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        val mask = segmenter.segment(bmp)
        assertNotNull(mask)
        assertEquals(2, mask.width)
        assertEquals(2, mask.height)
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f), mask.mask.toList())
        // second call at a different size reallocates the pixel buffer
        val bigger = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        assertNotNull(segmenter.segment(bigger))
        segmenter.close()
        assertEquals(7L, destroyedWith)
        segmenter.close() // idempotent: no second destroy
        assertEquals(7L, destroyedWith)
        // after close the segmenter is inert again
        assertNull(segmenter.segment(bmp))
    }

    @Test
    fun `segmenter whose creator fails stays unavailable`() {
        val segmenter = NativeSegmenter(
            context = context,
            assetName = "fake.maml",
            tag = "test",
            create = { 0L },
            readAsset = { byteArrayOf(1) },
        )
        assertFalse(segmenter.isAvailable)
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertNull(segmenter.segment(bmp))
        segmenter.close()

        val unreadable = NativeSegmenter(
            context = context,
            assetName = "fake.maml",
            tag = "test",
            create = { 9L },
            readAsset = { throw java.io.IOException("no asset") },
        )
        assertFalse(unreadable.isAvailable)
    }

    @Test
    fun `detector drives detect through its seam including hardware bitmaps`() {
        val detector = FaceDetector(context)
        var destroyed = false
        detector.destroyNative = { destroyed = true }
        detector.handle = 7L
        assertTrue(detector.isAvailable)
        detector.detectNative = { _, pixels, w, h ->
            assertEquals(w * h, pixels.size)
            floatArrayOf(
                0.1f, 0.2f, 0.5f, 0.6f,
                0.2f, 0.3f, 0.4f, 0.3f, 0.9f,
                0.3f, 0.4f, 0.25f, 0.5f, 0.45f, 0.5f,
            )
        }
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val faces = detector.detect(bmp)
        assertEquals(1, faces.size)
        assertEquals(0.9f, faces[0].score)
        // native returning null is "no faces", not an error
        detector.detectNative = { _, _, _, _ -> null }
        assertEquals(emptyList(), detector.detect(bmp))
        detector.close()
        assertTrue(destroyed)
        assertFalse(detector.isAvailable)
    }

    @Test
    fun `recognizer runs recognition through its seam`() {
        val recognizer = TextRecognizer(context)
        var destroyed = false
        recognizer.destroyNative = { destroyed = true }
        recognizer.handle = 7L
        assertTrue(recognizer.isAvailable)
        recognizer.ocrNative = { _, pixels, w, h ->
            assertEquals(w * h, pixels.size)
            "Line one\t0\t0\t10\t0\t10\t5\t0\t5\t0.9\t0"
        }
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val lines = recognizer.recognize(bmp)
        assertEquals(1, lines.size)
        assertEquals("Line one", lines.first().text)
        // a second, larger bitmap reallocates the shared pixel buffer
        val bigger = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888)
        assertEquals(1, recognizer.recognize(bigger).size)
        // native returning null is "no text", not an error
        recognizer.ocrNative = { _, _, _, _ -> null }
        assertEquals(emptyList(), recognizer.recognize(bmp))
        recognizer.close()
        assertTrue(destroyed)
        assertFalse(recognizer.isAvailable)
    }

    @Test
    fun `vision and audio towers encode through their seams`() {
        val dir = java.nio.file.Files.createTempDirectory("towers").toFile()
        val vision = Gemma4VisionHandle.inDirectory(dir)
        var visionDestroyed = false
        vision.destroyNative = { visionDestroyed = true }
        vision.handle = 7L
        assertTrue(vision.isAvailable)
        vision.sizeNative = { w, h, _ -> intArrayOf(w * 2, h * 2) }
        vision.encodeNative = { _, pixels, w, h ->
            assertEquals(w * h, pixels.size)
            FloatArray(Gemma4VisionHandle.OUT_DIM * 3) { 0.5f }
        }
        val image = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        val soft = vision.encode(image)
        assertNotNull(soft)
        assertEquals(Gemma4VisionHandle.OUT_DIM * 3, soft.size)
        // a malformed grid answer is a refusal, not a crash
        vision.sizeNative = { _, _, _ -> intArrayOf(1) }
        assertNull(vision.encode(image))
        vision.sizeNative = { _, _, _ -> null }
        assertNull(vision.encode(image))
        vision.close()
        assertTrue(visionDestroyed)

        val audio = Gemma4AudioHandle.inDirectory(dir)
        var audioDestroyed = false
        audio.destroyNative = { audioDestroyed = true }
        audio.handle = 7L
        // too-short clips are refused before the native call
        assertNull(audio.encode(FloatArray(10)))
        audio.encodeNative = { _, samples -> FloatArray(samples.size) { 0.25f } }
        val encoded = audio.encode(FloatArray(Gemma4AudioHandle.MIN_SAMPLES))
        assertNotNull(encoded)
        assertEquals(Gemma4AudioHandle.MIN_SAMPLES, encoded.size)
        audio.close()
        assertTrue(audioDestroyed)
        dir.deleteRecursively()
    }

    @Test
    fun `gemma create gauntlet rejects short files and opens full ones`() {
        // shortFiles is the public half of the check (#901): every present
        // file under its manifest size is named, missing ones are not
        val dir = java.nio.file.Files.createTempDirectory("gemma-gauntlet").toFile()
        assertEquals(emptyList(), shortFiles(dir))
        File(dir, Gemma4Handle.TEXT).writeBytes(byteArrayOf(1, 2))
        File(dir, Gemma4Handle.TOKENIZER).writeBytes(byteArrayOf(1))
        val short = shortFiles(dir)
        assertEquals(2, short.size)
        assertTrue(short.any { it.startsWith(Gemma4Handle.TEXT) })
        assertTrue(short.any { it.startsWith(Gemma4Handle.TOKENIZER) })

        // with every required file present at full (sparse) size the gauntlet
        // reaches the descriptor hand-off; in the sandbox the fd detach is
        // not faithfully shadowed and on the host the JNI create has no
        // symbols - either way construction cannot produce a handle here,
        // and the emulator suite covers the graceful device refusal
        for (name in listOf(Gemma4Handle.TEXT, Gemma4Handle.EMBED, Gemma4Handle.TOKENIZER)) {
            RandomAccessFile(File(dir, name), "rw").use {
                it.setLength(GEMMA4_EXPECTED_BYTES.getValue(name))
            }
        }
        assertEquals(emptyList(), shortFiles(dir))
        assertFailsWith<Throwable> { Gemma4Handle.inDirectory(dir) }
        dir.deleteRecursively()
    }
}
