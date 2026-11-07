package com.vayunmathur.speech.util

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import com.vayunmathur.speech.testing.ThrowingDetachFdShadow
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ThrowingDetachFdShadow::class])
class WhisperModelAndEngineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun modelConstantsPointAtBundledGraph() {
        assertEquals("whisper-base", WhisperModel.DIR)
        assertEquals("whisper_base.maml", WhisperModel.GRAPH)
    }

    @Test
    fun isReadyMatchesAssetListing() {
        val listed = context.assets.list(WhisperModel.DIR)?.toList().orEmpty()
        val expected = WhisperModel.GRAPH in listed
        assertEquals(expected, WhisperModel.isReady(context))
        if (expected) assertTrue(WhisperModel.isReady(context))
    }

    @Test
    fun isReadyIsFalseWhenAssetsThrow() {
        val throwing = object : ContextWrapper(context) {
            override fun getAssets(): AssetManager = throw IOException("boom")
        }
        assertFalse(WhisperModel.isReady(throwing))
    }

    @Test
    fun engineCannotTranscribeWithoutNativeModelAndCloseResets() {
        val engine = WhisperEngine(context)
        assertFalse(engine.isModelPresent())
        assertFalse(engine.preload())
        assertNull(engine.transcribe(ShortArray(1600), "en"))
        assertNull(engine.transcribe(ShortArray(1600), null))
        assertNull(engine.transcribe(ShortArray(0), "auto"))
        assertNull(engine.transcribe(ShortArray(1600), "ja-JP"))
        engine.close()
        // After close the load-failed latch is cleared, so it tries (and fails) again.
        assertFalse(engine.isModelPresent())
        engine.close()
    }

    @Test
    fun engineWithThrowingAssetsReportsUnavailable() {
        val throwing = object : ContextWrapper(context) {
            override fun getAssets(): AssetManager = throw IOException("boom")
            override fun getApplicationContext(): Context = this
        }
        val engine = WhisperEngine(throwing)
        assertFalse(engine.preload())
        assertNull(engine.transcribe(ShortArray(100), "en"))
        engine.close()
    }
}
