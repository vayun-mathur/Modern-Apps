package com.vayunmathur.library.ml

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Supertonic without its bundle: unavailable, silent, and its descriptor
 * ownership piece still honours release/close exactly once.
 */
@RunWith(RobolectricTestRunner::class)
class SupertonicTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `synthesizer in empty directory is unavailable and silent`() {
        val dir = java.nio.file.Files.createTempDirectory("supertonic-empty").toFile()
        val synth = SupertonicSynthesizer.inDirectory(dir)
        assertFalse(synth.isAvailable)
        assertEquals(44_100, synth.sampleRate)
        assertEquals(FloatArray(0).toList(), synth.synthesize("hello world", "en").toList())
        assertEquals(FloatArray(0).toList(), synth.synthesize("", "en").toList())
        assertEquals(FloatArray(0).toList(), synth.synthesize("   ", "fr").toList())
        assertFalse(synth.voice("M1"))
        synth.close(); synth.close()
    }

    @Test
    fun `synthesizer in assets without bundle is unavailable`() {
        val synth = SupertonicSynthesizer.inAssets(context.assets, path = "no-such-bundle")
        assertFalse(synth.isAvailable)
        assertEquals(FloatArray(0).toList(), synth.synthesize("bonjour", "fr").toList())
        synth.close()
        assertEquals("style_F1.bin", SupertonicSynthesizer.styleName("F1"))
        assertEquals("style_M2.bin", SupertonicSynthesizer.styleName("M2"))
        assertEquals("F1", SupertonicSynthesizer.DEFAULT_VOICE)
    }

    @Test
    fun `synthesizer with only some bundle files is unavailable`() {
        val dir = java.nio.file.Files.createTempDirectory("supertonic-partial").toFile()
        // No graph files: the first open() fails its isFile check before any
        // descriptor work (ParcelFileDescriptor.detachFd is broken under Robolectric).
        File(dir, "unicode_indexer.bin").writeBytes(byteArrayOf(4))
        File(dir, "style_F1.bin").writeBytes(byteArrayOf(5))
        val synth = SupertonicSynthesizer.inDirectory(dir)
        assertFalse(synth.isAvailable)
        synth.close()
    }

    @Test
    fun `piece releases its descriptor exactly once`() {
        // ParcelFileDescriptor.detachFd is broken under Robolectric (libcore
        // getOwnerId is absent on the host JDK), so synthetic descriptors stand
        // in: Piece's ownership logic is what is under test, not the fd itself.
        val released = SupertonicSynthesizer.Piece(-1, at = 0L, length = 3L)
        assertEquals(-1, released.release())
        released.close() // no-op after release; must not attempt a close

        val owned = SupertonicSynthesizer.Piece(-2, at = 5L, length = 3L)
        assertEquals(5L, owned.at)
        assertEquals(3L, owned.length)
        owned.close() // closes (adoptFd failure is swallowed by design)
        owned.close() // second close is a no-op, must not throw
    }
}
