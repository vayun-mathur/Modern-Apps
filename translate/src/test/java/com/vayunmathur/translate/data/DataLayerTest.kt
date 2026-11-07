package com.vayunmathur.translate.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.domain.MAX_RECENT_LANGUAGES
import com.vayunmathur.translate.domain.parseRecentLanguages
import com.vayunmathur.translate.domain.pushRecentLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DataLayerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- Languages -------------------------------------------------------------

    @Test
    fun `languages catalog shape and lookups`() {
        assertEquals(202, Languages.ALL.size)
        assertEquals(203, Languages.SOURCES.size)
        assertEquals(Languages.AUTO, Languages.SOURCES.first())
        assertEquals(Languages.ALL, Languages.TARGETS)
        assertEquals(202, Languages.ALL.map { it.code }.distinct().size)
        assertEquals(Languages.AUTO, Languages.byCode("auto"))
        assertEquals("en", Languages.byCode("en").code)
        assertEquals("zh-Hans", Languages.byCode("zh").code) // legacy split
        assertEquals("nso", Languages.byCode("ns").code) // legacy ISO fix
        assertEquals("nb", Languages.byCode("no").code) // legacy explicit Bokmal
        assertEquals("en", Languages.byCode("br").code) // no NLLB equivalent -> English
        assertEquals("en", Languages.byCode("totally-unknown").code)
        assertEquals(Languages.byCode("fr").nativeName, Languages.displayName("fr"))
        assertEquals("Auto", Languages.displayName("auto"))
        assertTrue(Languages.ALL.all { it.flores.contains('_') })
    }

    // --- RecentLanguages pure ------------------------------------------------------

    @Test
    fun `push recent moves to front dedupes caps and never stores auto`() {
        assertEquals(listOf("fr"), pushRecentLanguage(emptyList(), "fr"))
        assertEquals(listOf("de", "fr", "es"), pushRecentLanguage(listOf("fr", "es"), "de"))
        assertEquals(listOf("es", "fr"), pushRecentLanguage(listOf("fr", "es"), "es"))
        val full = (1..MAX_RECENT_LANGUAGES).map { "l$it" }
        val pushed = pushRecentLanguage(full, "new")
        assertEquals(MAX_RECENT_LANGUAGES, pushed.size)
        assertEquals("new", pushed.first())
        assertEquals(full, pushRecentLanguage(full, Languages.AUTO.code))
        assertEquals(emptyList<String>(), pushRecentLanguage(emptyList(), Languages.AUTO.code))
    }

    @Test
    fun `parse recents handles null blank and messy strings`() {
        assertEquals(emptyList(), parseRecentLanguages(null))
        assertEquals(emptyList(), parseRecentLanguages(""))
        assertEquals(emptyList(), parseRecentLanguages("   "))
        assertEquals(listOf("fr", "de"), parseRecentLanguages("fr,de"))
        assertEquals(listOf("fr", "de"), parseRecentLanguages(" fr , de "))
        assertEquals(listOf("fr"), parseRecentLanguages("fr,,"))
    }

    // --- TranslateSettings -------------------------------------------------------------

    @Test
    fun `settings round trip persists languages and recents`() = runBlocking {
        val settings = TranslateSettings(context)
        settings.setSource("fr")
        settings.setTarget("de")
        assertEquals("fr", settings.source())
        assertEquals("de", settings.target())

        settings.pushRecentSource("fr")
        settings.pushRecentSource("ja")
        settings.pushRecentSource("fr") // dedupe + front
        settings.pushRecentSource(Languages.AUTO.code) // never stored
        assertEquals(listOf("fr", "ja"), settings.recentSources())

        settings.pushRecentTarget("de")
        assertEquals(listOf("de"), settings.recentTargets())

        // Recents cap at MAX_RECENT_LANGUAGES.
        for (i in 1..10) settings.pushRecentTarget("t$i")
        assertEquals(MAX_RECENT_LANGUAGES, settings.recentTargets().size)
        assertEquals("t10", settings.recentTargets().first())
    }

    @Test
    fun `translation record data defaults`() {
        val record = TranslationRecord(
            sourceText = "hello", translatedText = "hola", sourceLang = "en", targetLang = "es",
        )
        assertEquals(0L, record.id)
        assertEquals(false, record.isFavorite)
        assertTrue(record.createdAt > 0)
        assertEquals(record, record.copy())
        assertNotNull(TRANSLATE_DB_NAME)
        assertEquals("translate-db", TRANSLATE_DB_NAME)
        assertEquals(200, TranslateRepository.MAX_HISTORY)
    }

    // --- Repository over Room ---------------------------------------------------------------

    @Test
    fun `repository is a process singleton`() {
        // Construction is lazy about the database itself; opening it needs the
        // AndroidKeyStore-backed SQLCipher key, which Robolectric does not have,
        // so record/history flows are only exercisable on a device.
        val repo = TranslateRepository.get(context)
        assertTrue(repo === TranslateRepository.get(context))
        assertEquals(200, TranslateRepository.MAX_HISTORY)
    }
}
