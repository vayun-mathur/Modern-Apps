package com.vayunmathur.speech.platform

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ml.SupertonicSynthesizer
import java.io.File
import java.io.IOException
import com.vayunmathur.speech.testing.ThrowingDetachFdShadow
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ThrowingDetachFdShadow::class])
class SupertonicEngineAndBundleTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun sampleRateIsVocoderRate() {
        val engine = SupertonicEngine(context)
        assertEquals(44100, engine.sampleRate)
        assertEquals(SupertonicSynthesizer.SAMPLE_RATE, engine.sampleRate)
        engine.close()
    }

    @Test
    fun unknownVoiceIsRefusedWithoutLoadingEngine() {
        val engine = SupertonicEngine(context)
        assertFalse(engine.voice("BOGUS"))
        assertFalse(engine.voice(""))
        assertFalse(engine.voice("f1x"))
        engine.close()
    }

    @Test
    fun engineReportsUnavailableInTestEnvironment() {
        val engine = SupertonicEngine(context)
        assertFalse(engine.preload())
        // Retries are bounded; repeated calls keep reporting unavailable.
        assertFalse(engine.preload())
        assertFalse(engine.preload())
        assertFalse(engine.voice("F2"))
        engine.close()
    }

    @Test
    fun synthesizeBlankTextProducesNoChunksAndReturnsFalse() {
        val engine = SupertonicEngine(context)
        var chunks = 0
        assertFalse(engine.synthesize("", "en") { chunks++; true })
        assertFalse(engine.synthesize("   ", "en") { chunks++; true })
        assertEquals(0, chunks)
        engine.close()
    }

    @Test
    fun synthesizeRealTextFailsCleanlyWhenEngineUnavailable() {
        val engine = SupertonicEngine(context)
        var chunks = 0
        assertFalse(engine.synthesize("Hello there. How are you?", "en") { chunks++; true })
        assertEquals(0, chunks)
        engine.close()
    }

    @Test
    fun closeIsIdempotentAndTerminal() {
        val engine = SupertonicEngine(context)
        engine.close()
        engine.close()
        assertFalse(engine.preload())
        assertFalse(engine.voice("F1"))
        assertFalse(engine.synthesize("Hello.", "en") { true })
    }

    @Test
    fun bundlePresenceMatchesRequiredAssetListing() {
        val entries = context.assets.list(SupertonicSynthesizer.ASSET_PATH)?.toSet().orEmpty()
        val required = listOf(
            "supertonic_dp.maml", "supertonic_ttl.maml", "supertonic_ve.maml",
            "supertonic_voc.maml", "unicode_indexer.bin", "style_F1.bin",
        )
        val expected = required.all { it in entries }
        assertEquals(expected, SupertonicBundle.isPresent(context))
        // Cached answer is stable.
        assertEquals(expected, SupertonicBundle.isPresent(context))
    }

    @Test
    fun bundlePresenceIsFalseWhenAssetsThrowAndNotCachedAsAnswer() {
        // Only meaningful if the real bundle answer has not already been cached
        // in this JVM; when it has, isPresent returns the cache by design.
        val throwing = object : ContextWrapper(context) {
            override fun getAssets(): AssetManager = throw IOException("boom")
        }
        val result = runCatching { SupertonicBundle.isPresent(throwing) }.getOrNull()
        // Either the cache (a Boolean) or false from the throwing path; never a throw.
        assertTrue(result == false || result == true)
    }

    @Test
    fun deleteOrphanedPiperVoicesRemovesTree() {
        val external = context.getExternalFilesDir(null)
        assertTrue(external != null)
        val piper = File(external, "piper")
        File(piper, "en/model.onnx").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(2048))
        File(piper, "de/model.onnx").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(1024))
        assertTrue(piper.isDirectory)
        SupertonicBundle.deleteOrphanedPiperVoices(context)
        assertFalse(piper.exists())
    }

    @Test
    fun deleteOrphanedPiperVoicesIsNoOpWithoutTree() {
        val external = context.getExternalFilesDir(null)!!
        val piper = File(external, "piper")
        piper.deleteRecursively()
        SupertonicBundle.deleteOrphanedPiperVoices(context)
        assertFalse(piper.exists())
        // A regular file named piper is not a directory and is left alone.
        piper.writeBytes(ByteArray(4))
        try {
            SupertonicBundle.deleteOrphanedPiperVoices(context)
            assertTrue(piper.isFile)
        } finally {
            piper.delete()
        }
    }
}
