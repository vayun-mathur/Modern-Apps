package com.vayunmathur.keyboard.domain
import com.vayunmathur.keyboard.data.KeyboardSettings

import com.vayunmathur.keyboard.platform.ime.NumericMode
import com.vayunmathur.keyboard.platform.ime.TextVariation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the Compose-[KeyboardType] parity added on top of the field-kind mapping:
 * every layout must classify as Latin/non-Latin, and the effective-layout/TextVariation
 * contract in [com.vayunmathur.keyboard.platform.ime.KeyboardState] must hold for the new
 * variants (PERSON_NAME caps, NO_SUGGESTIONS, ASCII fallback).
 */
class FieldKindParityTest {

    /** ASCII-forced fields keep plain-Latin user layouts and fall back otherwise. */
    @Test
    fun `effective layout keeps latin and falls back for other scripts`() {
        val state = com.vayunmathur.keyboard.platform.ime.KeyboardState()
        state.forceAscii = true

        state.settings = KeyboardSettings(activeLayoutId = "fr_azerty")
        assertEquals("fr_azerty", state.effectiveLayout.id)

        state.settings = KeyboardSettings(activeLayoutId = "ru")
        assertEquals("en_qwerty", state.effectiveLayout.id)

        state.settings = KeyboardSettings(activeLayoutId = "ja_romaji")
        assertEquals("en_qwerty", state.effectiveLayout.id)

        state.forceAscii = false
        state.settings = KeyboardSettings(activeLayoutId = "ru")
        assertEquals("ru", state.effectiveLayout.id)
    }

    /** Every catalog layout reports whether its first row is Latin script. */
    @Test
    fun `latin script classification covers the catalog`() {
        val latin = assertNotNull(KeyboardLayouts.byId("en_qwerty"))
        assertTrue(latin.isLatinScript)
        val cyrillic = assertNotNull(KeyboardLayouts.byId("ru"))
        assertFalse(cyrillic.isLatinScript)
        val arabic = assertNotNull(KeyboardLayouts.byId("ar"))
        assertFalse(arabic.isLatinScript)
        // Every transliteration layout whose rows are Latin still counts as Latin.
        for (layout in KeyboardLayouts.ALL) {
            val firstRowLatin =
                layout.rows.firstOrNull()?.any { it in 'A'..'Z' || it in 'a'..'z' } == true
            assertEquals(firstRowLatin, layout.isLatinScript, "${layout.id} misclassified")
        }
    }

    /** The new TextVariation/NumericMode members exist with the expected names. */
    @Test
    fun `parity enums carry the new variants`() {
        assertEquals(
            setOf("NORMAL", "EMAIL", "URL", "PERSON_NAME", "NO_SUGGESTIONS"),
            TextVariation.entries.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("NUMBER", "DATE", "TIME", "DATE_TIME"),
            NumericMode.entries.map { it.name }.toSet(),
        )
    }
}
