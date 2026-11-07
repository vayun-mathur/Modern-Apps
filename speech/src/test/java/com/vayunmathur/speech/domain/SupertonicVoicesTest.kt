package com.vayunmathur.speech.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupertonicVoicesTest {

    @Test
    fun catalogHas31UniqueLanguagesEnglishFirst() {
        assertEquals(31, SupertonicVoices.ALL.size)
        assertEquals("en", SupertonicVoices.ALL.first().code)
        assertEquals(SupertonicVoices.ALL.first(), SupertonicVoices.DEFAULT)
        assertEquals(31, SupertonicVoices.ALL.map { it.code }.toSet().size)
        assertEquals(31, SupertonicVoices.ALL.map { it.bcp47 }.toSet().size)
        assertEquals(31, SupertonicVoices.ALL.map { it.iso3 }.toSet().size)
        assertEquals(31, SupertonicVoices.ALL.map { it.iso3Country }.toSet().size)
        for (lang in SupertonicVoices.ALL) {
            assertEquals(2, lang.code.length, lang.code)
            assertEquals(3, lang.iso3.length, lang.iso3)
            assertEquals(3, lang.iso3Country.length, lang.iso3Country)
            assertTrue(lang.bcp47.startsWith(lang.code + "-"), lang.bcp47)
            assertTrue(lang.englishName.isNotBlank())
            assertTrue(lang.nativeName.isNotBlank())
        }
        // Rest after English are alphabetical by code.
        val rest = SupertonicVoices.ALL.drop(1).map { it.code }
        assertEquals(rest.sorted(), rest)
    }

    @Test
    fun voicesAreTenStylesWithF1Default() {
        assertEquals(listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"), SupertonicVoices.VOICES)
        assertEquals("F1", SupertonicVoices.DEFAULT_VOICE)
        assertTrue(SupertonicVoices.DEFAULT_VOICE in SupertonicVoices.VOICES)
    }

    @Test
    fun byCodeIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals("de", SupertonicVoices.byCode("de")?.code)
        assertEquals("de", SupertonicVoices.byCode("DE")?.code)
        assertEquals("pt", SupertonicVoices.byCode("Pt")?.code)
        assertNull(SupertonicVoices.byCode("th"))
        assertNull(SupertonicVoices.byCode("xx"))
        assertNull(SupertonicVoices.byCode(""))
        assertNull(SupertonicVoices.byCode("en-US"))
    }

    @Test
    fun byBcp47AcceptsCaseAndUnderscores() {
        assertEquals("en", SupertonicVoices.byBcp47("en-US")?.code)
        assertEquals("en", SupertonicVoices.byBcp47("EN-us")?.code)
        assertEquals("pt", SupertonicVoices.byBcp47("pt_BR")?.code)
        assertEquals("pt", SupertonicVoices.byBcp47("pt-BR")?.code)
        assertNull(SupertonicVoices.byBcp47("pt-PT"))
        assertNull(SupertonicVoices.byBcp47("th-TH"))
        assertNull(SupertonicVoices.byBcp47(""))
    }

    @Test
    fun byIso3IsCaseInsensitiveAndRejectsUnknown() {
        assertEquals("en", SupertonicVoices.byIso3("eng")?.code)
        assertEquals("de", SupertonicVoices.byIso3("DEU")?.code)
        assertEquals("cs", SupertonicVoices.byIso3("ces")?.code)
        assertNull(SupertonicVoices.byIso3("tha"))
        assertNull(SupertonicVoices.byIso3("en"))
        assertNull(SupertonicVoices.byIso3(""))
    }

    @Test
    fun everyLanguageRoundTripsThroughAllSpellings() {
        for (lang in SupertonicVoices.ALL) {
            assertEquals(lang, SupertonicVoices.byCode(lang.code))
            assertEquals(lang, SupertonicVoices.byBcp47(lang.bcp47))
            assertEquals(lang, SupertonicVoices.byIso3(lang.iso3))
            assertEquals(lang, SupertonicVoices.resolve(lang = lang.code))
            assertEquals(lang, SupertonicVoices.resolve(lang = lang.iso3))
            assertEquals(lang, SupertonicVoices.resolve(lang = lang.bcp47))
            assertEquals(lang, SupertonicVoices.resolve(voiceName = SupertonicVoices.voiceName(lang, "M2")))
        }
    }

    @Test
    fun voiceNameAndStyleExtraction() {
        val en = SupertonicVoices.DEFAULT
        assertEquals("en-US-x-F1", SupertonicVoices.voiceName(en, "F1"))
        assertEquals("de-DE-x-M5", SupertonicVoices.voiceName(SupertonicVoices.byCode("de")!!, "M5"))

        assertTrue(SupertonicVoices.namesAStyle("en-US-x-F1"))
        assertTrue(SupertonicVoices.namesAStyle("en-US-x-BOGUS"))
        assertFalse(SupertonicVoices.namesAStyle("en-US"))
        assertFalse(SupertonicVoices.namesAStyle(null))
        assertFalse(SupertonicVoices.namesAStyle(""))

        assertEquals("F1", SupertonicVoices.styleIn("en-US-x-F1"))
        assertEquals("M3", SupertonicVoices.styleIn("de-DE-x-m3"))
        assertEquals("F2", SupertonicVoices.styleIn("fr-FR-x-F2"))
        assertNull(SupertonicVoices.styleIn("en-US-x-BOGUS"))
        assertNull(SupertonicVoices.styleIn("en-US"))
        assertNull(SupertonicVoices.styleIn(null))
        assertNull(SupertonicVoices.styleIn("en-US-x-"))
        assertNull(SupertonicVoices.styleIn("en-US-x-F1-extra"))
        assertNull(SupertonicVoices.styleIn(""))
    }

    @Test
    fun resolvePrefersVoiceNameOverLanguage() {
        val found = SupertonicVoices.resolve(lang = "de", voiceName = "fr-FR-x-M1")
        assertEquals("fr", found?.code)
    }

    @Test
    fun resolveStripsVoiceStyleBeforeReadingTag() {
        assertEquals("en", SupertonicVoices.resolve(voiceName = "en-US-x-F4")?.code)
        assertEquals("ja", SupertonicVoices.resolve(voiceName = "ja-JP-x-F1")?.code)
        // A bogus style still leaves a readable language tag.
        assertEquals("en", SupertonicVoices.resolve(voiceName = "en-US-x-NOPE")?.code)
    }

    @Test
    fun resolveFallsThroughFromBadVoiceNameToLanguage() {
        assertEquals("de", SupertonicVoices.resolve(lang = "de", voiceName = "th-TH-x-F1")?.code)
        assertEquals("de", SupertonicVoices.resolve(lang = "de", voiceName = "xx")?.code)
    }

    @Test
    fun resolveJoinedPartsBeatSeparateParts() {
        assertEquals("en", SupertonicVoices.resolve(lang = "en", country = "US")?.code)
        assertEquals("pt", SupertonicVoices.resolve(lang = "pt", country = "BR")?.code)
        assertEquals("de", SupertonicVoices.resolve(lang = "de", country = "DE", variant = "x")?.code)
        // Empty country/variant are ignored when joining.
        assertEquals("fr", SupertonicVoices.resolve(lang = "fr", country = "", variant = "")?.code)
        assertEquals("fr", SupertonicVoices.resolve(lang = "fr", country = null, variant = null)?.code)
    }

    @Test
    fun resolveAcceptsRegionVariantsByLanguageHead() {
        // One Portuguese voice: pt-PT resolves to pt-BR's language rather than nothing.
        assertEquals("pt", SupertonicVoices.resolve(lang = "pt-PT")?.code)
        assertEquals("pt", SupertonicVoices.resolve(lang = "pt", country = "PT")?.code)
        assertEquals("en", SupertonicVoices.resolve(lang = "en-GB")?.code)
        assertEquals("en", SupertonicVoices.resolve(lang = "en_GB")?.code)
        assertEquals("es", SupertonicVoices.resolve(lang = "es-MX")?.code)
        assertEquals("de", SupertonicVoices.resolve(voiceName = "de-AT-x-F1")?.code)
    }

    @Test
    fun resolveAcceptsIso3AndUppercase() {
        assertEquals("en", SupertonicVoices.resolve(lang = "eng")?.code)
        assertEquals("en", SupertonicVoices.resolve(lang = "ENG")?.code)
        assertEquals("en", SupertonicVoices.resolve(lang = "eng", country = "USA")?.code)
        assertEquals("uk", SupertonicVoices.resolve(lang = "UKR")?.code)
        assertEquals("de", SupertonicVoices.resolve(lang = "DE")?.code)
    }

    @Test
    fun resolveReturnsNullForUnsupportedOrMissing() {
        assertNull(SupertonicVoices.resolve(lang = "th"))
        assertNull(SupertonicVoices.resolve(lang = "zh"))
        assertNull(SupertonicVoices.resolve(lang = "tha"))
        assertNull(SupertonicVoices.resolve(lang = "abcd"))
        assertNull(SupertonicVoices.resolve(lang = "e"))
        assertNull(SupertonicVoices.resolve())
        assertNull(SupertonicVoices.resolve(lang = null))
        assertNull(SupertonicVoices.resolve(lang = ""))
        assertNull(SupertonicVoices.resolve(lang = "", country = "US"))
        assertNull(SupertonicVoices.resolve(voiceName = ""))
        assertNull(SupertonicVoices.resolve(voiceName = "th-TH"))
    }

    @Test
    fun languageDataClassEqualityAndCopy() {
        val en = SupertonicVoices.DEFAULT
        assertEquals(en, en.copy())
        assertNotEquals(en, en.copy(code = "xx"))
        assertEquals("English", en.englishName)
        assertEquals("en-US", en.bcp47)
        assertEquals("eng", en.iso3)
        assertEquals("USA", en.iso3Country)
        val (code, bcp47, iso3, country, english, native) = en
        assertEquals(listOf("en", "en-US", "eng", "USA", "English", "English"),
            listOf(code, bcp47, iso3, country, english, native))
    }
}
