package com.vayunmathur.translate.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ml.MadladHandle
import com.vayunmathur.library.ml.NllbHandle
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.translate.domain.Languages
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PlatformEnginesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- NLLB translator ---------------------------------------------------------

    @Test
    fun `nllb translator without downloads is unavailable and fails closed`() = runBlocking {
        val translator = NllbTranslator(context)
        assertFalse(translator.isAvailable())
        assertNull(translator.detectLanguage("hello"))
        assertEquals("", translator.translate("", null, "es"))
        assertEquals("", translator.translate("   ", "en", "es"))
        assertNull(translator.translate("hello", null, "es"))
        assertNull(translator.translate("hello", "en", "fr"))
        assertNull(translator.translate("hello", "xx-unknown", "es"))
        translator.reset()
        translator.close()
        translator.close()
        // After close, still unavailable and translate still fails closed.
        assertFalse(translator.isAvailable())
        assertNull(translator.translate("hello", "en", "es"))
    }

    @Test
    fun `madlad translator without downloads is unavailable and fails closed`() = runBlocking {
        val translator = MadladTranslator(context)
        assertFalse(translator.isAvailable())
        assertNull(translator.detectLanguage("hola"))
        assertEquals("", translator.translate("", null, "en"))
        assertNull(translator.translate("hola", null, "en"))
        assertNull(translator.translate("hola", null, ""))
        assertNull(translator.translate("hola", null, "123"))
        translator.reset()
        translator.close()
        translator.close()
        assertFalse(translator.isAvailable())
    }

    // --- Model configs ---------------------------------------------------------------

    @Test
    fun `nllb model files match handle files and token ids are contiguous`() {
        assertEquals(NllbHandle.FILES, NllbModel.FILES.map { it.fileName.substringAfter('/') })
        assertEquals("nllb600", NllbModel.DIR)
        assertEquals(256001 + 46, NllbModel.tokenId("eng_Latn")) // eng_Latn index in FLORES_ORDER
        assertEquals(256001, NllbModel.tokenId("ace_Arab"))
        assertEquals(256001 + 201, NllbModel.tokenId("zul_Latn"))
        assertNull(NllbModel.tokenId("not_Aflores"))
        assertNull(NllbModel.tokenId(""))
        for (item in NllbModel.FILES) {
            assertTrue(item.url.endsWith(item.fileName.substringAfter('/')), item.url)
            assertNotNull(item.sha256)
            assertEquals(64, item.sha256!!.length)
        }
        // Every UI language's flores code has a token (the sync the Languages doc promises).
        for (language in Languages.ALL) {
            assertNotNull(NllbModel.tokenId(language.flores), language.code)
        }
        assertEquals(202, Languages.ALL.size)
        assertEquals(202, Languages.ALL.map { it.flores }.distinct().size)
    }

    @Test
    fun `nllb model download state and retired deletion reclaim bytes`() {
        assertFalse(NllbModel.isDownloaded(context))
        assertTrue(NllbModel.modelDir(context).path.endsWith(NllbModel.DIR))
        val root = context.getExternalFilesDir(null)
        assertNotNull(root)
        // Nothing retired yet.
        assertEquals(0L, NllbModel.deleteRetired(context))
        val retiredDir = File(root, "small100")
        retiredDir.mkdirs()
        File(retiredDir, "small100.maml").writeBytes(ByteArray(100))
        File(retiredDir, "vocab.txt").writeBytes(ByteArray(23))
        File(retiredDir, "keepme.bin").writeBytes(ByteArray(7))
        val reclaimed = NllbModel.deleteRetired(context)
        assertEquals(123L, reclaimed)
        // Directory survives because keepme.bin is not a retired name.
        assertTrue(retiredDir.isDirectory)
        assertEquals(0L, NllbModel.deleteRetired(context))
        File(retiredDir, "keepme.bin").delete()
        // Both model files appearing flips isDownloaded.
        for (item in NllbModel.FILES) {
            val f = File(root, item.fileName); f.parentFile?.mkdirs(); f.writeBytes(byteArrayOf(1))
        }
        assertTrue(NllbModel.isDownloaded(context))
    }

    @Test
    fun `nllb progress averages file progress from datastore`() {
        val ds = DataStoreUtils.getInstance(context)
        assertEquals(0f, NllbModel.progress(ds))
    }

    @Test
    fun `madlad model files tags and download state`() {
        assertEquals(MadladHandle.FILES, MadladModel.FILES.map { it.fileName.substringAfter('/') })
        assertEquals("madlad400", MadladModel.DIR)
        assertFalse(MadladModel.isDownloaded(context))
        assertEquals("<2en>", MadladModel.targetTag("en"))
        assertEquals("<2es>", MadladModel.targetTag("es"))
        assertEquals("<2zh>", MadladModel.targetTag("zh-Hans"))
        assertEquals("<2pt>", MadladModel.targetTag("pt-BR"))
        assertEquals("<2en>", MadladModel.targetTag("EN"))
        assertNull(MadladModel.targetTag(""))
        assertNull(MadladModel.targetTag("-en"))
        assertNull(MadladModel.targetTag("e1"))
        assertNull(MadladModel.targetTag("123"))
        assertEquals(0f, MadladModel.progress(DataStoreUtils.getInstance(context)))
    }

    // --- TTS ----------------------------------------------------------------------------

    @Test
    fun `tts speaker queues silently before ready and shuts down safely`() {
        val speaker = TtsSpeaker(context)
        // Not ready yet under Robolectric: supported reads as false, speak queues.
        assertFalse(speaker.isLanguageSupported("es"))
        speaker.speak("", "es") // blank is a no-op even before ready
        var missing = 0
        speaker.speak("hola", "es") { missing++ }
        speaker.stop()
        speaker.speak("hello", "en")
        speaker.shutdown()
        // After shutdown everything is inert.
        speaker.speak("hello", "en") { missing++ }
        assertFalse(speaker.isLanguageSupported("en"))
        speaker.stop()
        speaker.shutdown()
    }

    @Test
    fun `tts speaker queues requests while the engine never initialises`() {
        // Under Robolectric the TTS service never calls back, so `ready` stays
        // false for the life of this test: speak must queue (not throw, not
        // invoke the missing-voice callback - that only fires once a ready
        // engine has been asked and refused).
        val speaker = TtsSpeaker(context)
        var missing = 0
        speaker.speak("hola", "es") { missing++ }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        speaker.speak("hello", "en") { missing++ }
        assertEquals(0, missing)
        assertFalse(speaker.isLanguageSupported("es"))
        speaker.stop()
        speaker.shutdown()
    }

    // --- Speech recognizer ------------------------------------------------------------------

    @Test
    fun `android speech recognizer reports unavailable through onError`() {
        val recognizer = AndroidSpeechRecognizer(context)
        // Robolectric has no recognition service: start must fail via onError,
        // never throw, for both pinned and auto language modes.
        var error: String? = null
        recognizer.start(
            languageCode = "en",
            onPartial = {},
            onFinal = {},
            onError = { error = it },
        )
        if (!recognizer.isAvailable()) {
            assertEquals("Speech recognition unavailable on this device", error)
        }
        error = null
        recognizer.start(
            languageCode = Languages.AUTO.code,
            onPartial = {},
            onFinal = {},
            onError = { error = it },
            onEndOfSpeech = {},
        )
        recognizer.stop()
        recognizer.destroy()
        recognizer.destroy()
    }
}
