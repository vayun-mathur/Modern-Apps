package com.vayunmathur.library.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3 (library/ml): the pure-Kotlin surfaces behind the native handles —
 * OCR result unpacking, face-box unpacking, the music gate's inert state,
 * and Gemma4Handle's unavailable-state contract — on the host JVM, where
 * `libmodelrunner.so` exports no symbols.
 */
@RunWith(RobolectricTestRunner::class)
class Pass3HostTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun tempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    // --- TextRecognizer.parse (internal seam) --------------------------------

    @Test
    fun `ocr parse unpacks lines with corners confidence and orientation`() {
        val packed = listOf(
            "Hello world\t0\t0\t100\t0\t100\t20\t0\t20\t0.95\t0",
            "Vertical\t5\t6\t7\t8\t9\t10\t11\t12\t0.5\t1",
        ).joinToString("\n")
        val lines = TextRecognizer.parse(packed)
        assertEquals(2, lines.size)
        val first = lines[0]
        assertEquals("Hello world", first.text)
        assertEquals(0.95f, first.confidence)
        assertFalse(first.vertical)
        assertEquals(0f, first.left)
        assertEquals(0f, first.top)
        assertEquals(100f, first.right)
        assertEquals(20f, first.bottom)
        assertTrue(lines[1].vertical)
        assertEquals(5f, lines[1].left)
        assertEquals(12f, lines[1].bottom)
    }

    @Test
    fun `ocr parse drops malformed rows and empty input`() {
        assertEquals(emptyList(), TextRecognizer.parse(""))
        val packed = listOf(
            "too\tfew\tfields",
            "bad numbers\tx\t0\t100\t0\t100\t20\t0\t20\t0.9\t0",
            "good\t1\t2\t3\t4\t5\t6\t7\t8\t0.25\t0",
            "",
        ).joinToString("\n")
        val lines = TextRecognizer.parse(packed)
        assertEquals(1, lines.size)
        assertEquals("good", lines.first().text)
        assertEquals(0.25f, lines.first().confidence)
    }

    // --- FaceDetector.unpack (internal seam) ---------------------------------

    @Test
    fun `face unpack maps fifteen floats per face`() {
        val detector = FaceDetector(context)
        assertFalse(detector.isAvailable)
        val flat = floatArrayOf(
            0.1f, 0.2f, 0.5f, 0.6f, 0.2f, 0.3f, 0.4f, 0.3f, 0.9f, 0.3f, 0.4f, 0.25f, 0.5f, 0.45f, 0.5f,
            0.6f, 0.1f, 0.9f, 0.4f, 0.7f, 0.2f, 0.85f, 0.2f, 0.8f, 0.75f, 0.25f, 0.7f, 0.35f, 0.9f, 0.35f,
        )
        val faces = detector.unpack(flat)
        assertEquals(2, faces.size)
        assertEquals(0.1f, faces[0].left)
        assertEquals(0.6f, faces[0].bottom)
        assertEquals(0.9f, faces[0].score)
        assertEquals(0.45f, faces[0].mouthRightX)
        assertEquals(0.85f, faces[1].rightEyeX)
        // a trailing partial face is refused wholesale, not part-unpacked
        assertEquals(emptyList(), detector.unpack(floatArrayOf(1f, 2f, 3f)))
        assertEquals(emptyList(), detector.unpack(FloatArray(0)))
        // detect on an unavailable detector is empty, and close is idempotent
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertEquals(emptyList(), detector.detect(bmp))
        detector.close()
        detector.close()
    }

    @Test
    fun `face embedder on an unavailable segmenter returns null`() {
        val embedder = FaceEmbedder(context)
        assertFalse(embedder.isAvailable)
        val bmp = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        assertNull(embedder.embed(bmp))
        embedder.close()
        assertEquals(512, FaceEmbedder.EMBEDDING_LENGTH)
    }

    // --- MusicGate ------------------------------------------------------------

    @Test
    fun `music gate constants describe the streaming contract`() {
        assertEquals(16_000, MusicGate.SAMPLE_RATE)
        assertEquals(160, MusicGate.HOP_SAMPLES)
        // warm-up arithmetic the streaming contract documents
        assertEquals(
            MusicGate.FRAME_SAMPLES + MusicGate.HOP_SAMPLES * (MusicGate.RECEPTIVE_FIELD_HOPS - 1),
            MusicGate.WARMUP_SAMPLES,
        )
        assertEquals(3_920, MusicGate.WARMUP_SAMPLES)
        // construction itself is a native call (CPU gate, no asset): on the
        // host the android-only JNI bridge throws, on a device it builds the
        // gate - the emulator suite covers the live path
        assertFailsWith<UnsatisfiedLinkError> { MusicGate.inProcess() }
    }

    // --- Gemma4Handle unavailable surface ------------------------------------

    @Test
    fun `gemma handle construction checks files before native on the host`() {
        // On the host the library loads but the android-only Gemma4 JNI
        // symbols are absent, so construction gets as far as the file checks
        // in createGemma4Handle and then dies reading the native error -
        // the emulator suite covers the graceful device path. Both the
        // missing-file and truncated-file rejections must fire first.
        val empty = tempDir("gemma-empty")
        assertFailsWith<UnsatisfiedLinkError> { Gemma4Handle.inDirectory(empty) }
        val short = tempDir("gemma-short")
        File(short, Gemma4Handle.TEXT).writeBytes(byteArrayOf(1))
        File(short, Gemma4Handle.EMBED).writeBytes(byteArrayOf(1))
        File(short, Gemma4Handle.TOKENIZER).writeBytes(byteArrayOf(1))
        assertFailsWith<UnsatisfiedLinkError> { Gemma4Handle.inDirectory(short) }
        assertEquals(
            listOf(Gemma4Handle.TEXT, Gemma4Handle.EMBED, Gemma4Handle.TOKENIZER),
            Gemma4Handle.FILES,
        )
        assertEquals(4, Gemma4Handle.FILES_WITH_HEAD.size)
        empty.deleteRecursively()
        short.deleteRecursively()
    }

    @Test
    fun `gemma prompt ceiling and digest arithmetic`() {
        assertEquals(
            Gemma4Handle.MAX_CONTEXT - Gemma4Handle.DEFAULT_REPLY,
            Gemma4Handle.promptCeiling(),
        )
        // a tiny reply still reserves the minimum, not the reply size
        assertEquals(Gemma4Handle.MAX_CONTEXT - 3, Gemma4Handle.promptCeiling(1))
        assertEquals(Gemma4Handle.MAX_CONTEXT - 100, Gemma4Handle.promptCeiling(100))

        val bytes = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        assertEquals(0x12345678, Gemma4Handle.readInt(bytes, 0))
        val digest = Gemma4Handle.digest(intArrayOf(1, 2, 3))
        assertEquals(32, digest.size)
        assertTrue(digest.contentEquals(Gemma4Handle.digest(intArrayOf(1, 2, 3))))
        assertFalse(digest.contentEquals(Gemma4Handle.digest(intArrayOf(1, 2, 4))))
        assertFalse(digest.contentEquals(Gemma4Handle.digest(IntArray(0))))
    }

    @Test
    fun `gemma vision and audio handles degrade without the library`() {
        val dir = tempDir("gemma-media")
        val vision = Gemma4VisionHandle.inDirectory(dir)
        assertFalse(vision.isAvailable)
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        assertNull(vision.encode(bmp))
        vision.close()
        vision.close()

        val audio = Gemma4AudioHandle.inDirectory(dir)
        assertFalse(audio.isAvailable)
        assertNull(audio.encode(FloatArray(1600)))
        audio.close()
        dir.deleteRecursively()
    }

    // --- Supertonic pieces -----------------------------------------------------

    @Test
    fun `supertonic is unavailable and its descriptor piece honors ownership`() {
        val synth = SupertonicSynthesizer.inDirectory(tempDir("supertonic-empty"))
        assertFalse(synth.isAvailable)
        assertEquals(SupertonicSynthesizer.SAMPLE_RATE, synth.sampleRate)
        assertEquals(FloatArray(0).toList(), synth.synthesize("hello", "en").toList())
        assertEquals(FloatArray(0).toList(), synth.synthesize("  ", "en").toList())
        assertFalse(synth.voice("F2"))
        synth.close()
        synth.close()
        assertEquals("style_F2.bin", SupertonicSynthesizer.styleName("F2"))

        // Piece: release hands the fd to native (close becomes a no-op);
        // without release, close closes it exactly once. Raw fds come from a
        // FileInputStream reflectively - ParcelFileDescriptor.detachFd is
        // not faithfully shadowed in the sandbox.
        val file = File(tempDir("piece"), "x.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val keepAlive = mutableListOf<ParcelFileDescriptor>()
        fun rawFd(): Int {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            keepAlive += pfd
            return pfd.fd
        }
        val piece = SupertonicSynthesizer.Piece(rawFd(), at = 0L, length = 3L)
        assertEquals(3L, piece.length)
        piece.close()
        piece.close() // second close must not double-close a recycled fd number

        val fd2 = rawFd()
        val released = SupertonicSynthesizer.Piece(fd2, at = 5L, length = 7L)
        assertEquals(fd2, released.release())
        released.close() // no-op after release; the caller owns fd2 now
        // (prod's own closeFd swallows sandbox fd quirks via runCatching;
        // here the owning PFD from rawFd is closed by the sandbox at teardown)
    }

    @Test
    fun `segmenters are unavailable and inert on the host`() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val selfie = SelfieSegmenter(context)
        assertFalse(selfie.isAvailable)
        assertNull(selfie.segment(bmp))
        selfie.close()
        val subject = SubjectSegmenter(context)
        assertFalse(subject.isAvailable)
        assertNull(subject.segment(bmp))
        subject.close()
        val modnet = ModnetSegmenter(context)
        assertFalse(modnet.isAvailable)
        assertNull(modnet.segment(bmp))
        modnet.close()
        val recognizer = TextRecognizer(context)
        assertFalse(recognizer.isAvailable)
        assertEquals(emptyList(), recognizer.recognize(bmp))
        recognizer.close()
        recognizer.close()
    }

    @Test
    fun `ml native library loads on the host but exports no gemma symbols`() {
        // The host .so exists (rustHostLibs), so the library loads; the JNI
        // bridge is cfg(target_os = "android"), so the Gemma4 externals are
        // absent - which is exactly why the pipeline needs the backend seam
        // and the emulator suite.
        assertTrue(MlNative.isAvailable)
        assertFailsWith<UnsatisfiedLinkError> { MlNative.lastGemma4Error() }
    }
}
