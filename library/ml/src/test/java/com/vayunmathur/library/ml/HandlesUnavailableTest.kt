package com.vayunmathur.library.ml

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every handle built without its model files: construction never throws, the
 * handle reports unavailable, and every operation degrades to its empty value.
 * This is the contract callers gate features on.
 */
@RunWith(RobolectricTestRunner::class)
class HandlesUnavailableTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun tempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    // --- Whisper ------------------------------------------------------------

    @Test
    fun `whisper without asset is unavailable and inert`() {
        val handle = WhisperHandle.inAssets(
            context.assets,
            special = intArrayOf(1, 2, 3, 4, 5),
            languages = intArrayOf(10, 11),
            suppress = intArrayOf(20),
            suppressAtBegin = intArrayOf(21),
        )
        assertFalse(handle.isAvailable)
        assertNull(handle.transcribe(FloatArray(WhisperHandle.MELS * WhisperHandle.FRAMES), -1))
        assertNull(handle.transcribe(FloatArray(10), -1))
        handle.close()
        handle.close() // idempotent
        assertTrue(handle.toString().contains("whisper-base"))
    }

    @Test
    fun `whisper rejects the wrong number of special ids`() {
        val handle = WhisperHandle.inAssets(
            context.assets,
            special = intArrayOf(1, 2),
            languages = intArrayOf(),
            suppress = intArrayOf(),
            suppressAtBegin = intArrayOf(),
        )
        assertFalse(handle.isAvailable)
        handle.close()
        assertEquals(80, WhisperHandle.MELS)
        assertEquals(3000, WhisperHandle.FRAMES)
        assertEquals(5, WhisperHandle.SPECIAL_IDS)
        assertTrue(WhisperHandle.GRAPH.endsWith(".maml"))
    }

    // --- TinyCLIP / Maia / Nnfp ------------------------------------------------

    @Test
    fun `clip without asset returns null embeddings`() {
        val handle = ClipHandle.inAssets(context.assets)
        assertFalse(handle.isAvailable)
        assertNull(handle.imageEmbedding(FloatArray(3 * 224 * 224)))
        assertNull(handle.textEmbedding(intArrayOf(1, 2, 3)))
        assertNull(handle.textEmbedding(intArrayOf()))
        handle.close()
        assertTrue(handle.toString().contains("TinyCLIP"))
        assertEquals(512, ClipHandle.DIMENSION)
        assertEquals(77, ClipHandle.CONTEXT_LENGTH)
    }

    @Test
    fun `maia without asset returns null logits`() {
        val handle = MaiaHandle.inAssets(context.assets)
        assertFalse(handle.isAvailable)
        assertNull(handle.logits(FloatArray(MaiaHandle.PLANE_COUNT * MaiaHandle.SQUARES), 1500, 1500))
        assertNull(handle.logits(FloatArray(10), 0, 5000))
        handle.close()
        assertTrue(handle.toString().contains("Maia3-5M"))
        assertEquals(4352, MaiaHandle.MOVES)
    }

    @Test
    fun `nnfp without asset returns null embeddings`() {
        val handle = NnfpHandle.inAssets(context.assets)
        assertFalse(handle.isAvailable)
        assertNull(handle.embed(ShortArray(NnfpHandle.WINDOW_SAMPLES)))
        assertNull(handle.embed(ShortArray(100)))
        handle.close()
        assertTrue(handle.toString().contains("fingerprinter"))
        assertEquals(64, NnfpHandle.EMBEDDING)
        assertEquals(
            NnfpHandle.FRAME_SAMPLES + NnfpHandle.HOP_SAMPLES * (NnfpHandle.WINDOW_FRAMES - 1),
            NnfpHandle.WINDOW_SAMPLES,
        )
    }

    // --- NLLB / MADLAD -----------------------------------------------------------

    @Test
    fun `nllb in empty and partial directories is unavailable`() {
        val empty = NllbHandle.inDirectory(tempDir("nllb-empty"))
        assertFalse(empty.isAvailable)
        assertNull(empty.translate("hello", 1, 2))
        assertNull(empty.translate("", 1, 2)) // unavailable wins over the blank shortcut
        empty.close()
        assertTrue(empty.toString().contains("NLLB-200"))
        assertEquals(listOf(NllbHandle.GRAPH, NllbHandle.TOKENIZER), NllbHandle.FILES)

        val partial = tempDir("nllb-partial")
        File(partial, NllbHandle.GRAPH).writeBytes(byteArrayOf(1, 2, 3))
        val graphOnly = NllbHandle.inDirectory(partial)
        assertFalse(graphOnly.isAvailable)
        graphOnly.close()
    }

    @Test
    fun `madlad in empty directory is unavailable`() {
        val empty = MadladHandle.inDirectory(tempDir("madlad-empty"))
        assertFalse(empty.isAvailable)
        assertNull(empty.translate("hello", "<2en>"))
        empty.close()
        assertTrue(empty.toString().contains("MADLAD400"))
        assertEquals(listOf(MadladHandle.GRAPH, MadladHandle.TOKENIZER), MadladHandle.FILES)
    }

    // --- Gemma towers ---------------------------------------------------------------

    @Test
    fun `gemma audio tower without file is unavailable`() {
        val tower = Gemma4AudioHandle.inDirectory(tempDir("gemma-audio"))
        assertFalse(tower.isAvailable)
        assertNull(tower.encode(FloatArray(Gemma4AudioHandle.MIN_SAMPLES + 100)))
        assertNull(tower.encode(FloatArray(10)))
        tower.close()
        assertTrue(tower.toString().contains("audio tower"))
        assertEquals(1536, Gemma4AudioHandle.OUT_DIM)
        assertTrue(Gemma4AudioHandle.MIN_SAMPLES > 7200)

        val dir = tempDir("gemma-audio-bogus")
        File(dir, Gemma4AudioHandle.AUDIO).writeBytes(byteArrayOf(1, 2, 3))
        val bogus = Gemma4AudioHandle.inDirectory(dir)
        assertFalse(bogus.isAvailable)
        bogus.close()
    }

    @Test
    fun `gemma vision tower without file is unavailable`() {
        val tower = Gemma4VisionHandle.inDirectory(tempDir("gemma-vision"))
        assertFalse(tower.isAvailable)
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        assertNull(tower.encode(bitmap))
        assertNull(tower.encode(bitmap, Gemma4VisionHandle.SOFT_TOKEN_BUDGETS.first()))
        tower.close()
        assertTrue(tower.toString().contains("vision tower"))
        assertEquals(intArrayOf(70, 140, 280, 560, 1120).toList(), Gemma4VisionHandle.SOFT_TOKEN_BUDGETS.toList())
        bitmap.recycle()
    }

    // --- Gemma 4 main handle -------------------------------------------------------------

    // Gemma4Handle's constructor reads MlNative.lastGemma4Error() when a load fails;
    // that export is absent from the host .so this JVM loads (stale Rust build), so
    // constructing the handle here throws UnsatisfiedLinkError. Its companion's pure
    // functions are covered in Gemma4PureTest; the handle itself is covered on-device.

    // --- MusicGate ---------------------------------------------------------------------------

    @Test
    fun `music gate constants are self consistent`() {
        assertEquals(16_000, MusicGate.SAMPLE_RATE)
        assertEquals(
            MusicGate.FRAME_SAMPLES + MusicGate.HOP_SAMPLES * (MusicGate.RECEPTIVE_FIELD_HOPS - 1),
            MusicGate.WARMUP_SAMPLES,
        )
        assertEquals(160, MusicGate.HOP_SAMPLES)
        assertEquals(400, MusicGate.FRAME_SAMPLES)
        // MusicGate.inProcess() calls MlNative.createMusicGate, whose export is absent
        // from the stale host .so (see the Gemma note above); construction is therefore
        // not exercised on the JVM here.
    }

    // --- MlNative flag ---------------------------------------------------------------------------

    @Test
    fun `native availability flag is readable`() {
        // Accessing it performs the one loadLibrary attempt for the whole process.
        val available: Boolean = MlNative.isAvailable
        assertEquals(available, MlNative.isAvailable)
    }
}
