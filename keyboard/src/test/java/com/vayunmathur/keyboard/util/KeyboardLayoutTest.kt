package com.vayunmathur.keyboard.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the layout catalogue, which is the sort of data that breaks silently: a shifted row
 * one character out of step mislabels every key after it, and nothing in the UI would notice.
 */
class KeyboardLayoutTest {

    @Test
    fun `ids are unique`() {
        val ids = KeyboardLayouts.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate layout id")
    }

    /** Three rows, or four for the layouts that take over the digit row (注音, JIS kana). */
    @Test
    fun `every layout has three or four non-empty rows`() {
        for (layout in KeyboardLayouts.ALL) {
            assertTrue(layout.rows.size in 3..4, "${layout.id} has ${layout.rows.size} rows")
            assertTrue(layout.rows.all { it.isNotEmpty() }, "${layout.id} has an empty row")
        }
    }

    /** A shift layer is positional, so a row that is a character short shifts every key after it. */
    @Test
    fun `shifted rows line up with base rows`() {
        for (layout in KeyboardLayouts.ALL) {
            val shifted = layout.shiftedRows ?: continue
            assertEquals(layout.rows.size, shifted.size, "${layout.id} shifted row count")
            layout.rows.forEachIndexed { i, row ->
                assertEquals(row.length, shifted[i].length, "${layout.id} shifted row $i length")
            }
        }
    }

    @Test
    fun `no layout repeats a character`() {
        for (layout in KeyboardLayouts.ALL) {
            val chars = layout.rows.joinToString("").toList()
            assertEquals(chars.size, chars.toSet().size, "${layout.id} repeats a key")
        }
    }

    /** Long-pressing a key that isn't on the layout can never fire. */
    @Test
    fun `alternates are keyed by characters the layout actually has`() {
        for (layout in KeyboardLayouts.ALL) {
            val chars = layout.rows.joinToString("").toSet()
            for (base in layout.alternates.keys) {
                assertTrue(base in chars, "${layout.id} has an alternate for the absent key '$base'")
            }
        }
    }

    /**
     * Same rule for the shared symbol/digit maps, which are keyed off the symbol pages and
     * the two punctuation keys beside the space bar rather than off a layout.
     */
    @Test
    fun `symbol and digit alternates are keyed by characters those pages have`() {
        val available = (Layouts.SYMBOL_ROWS + Layouts.MORE_SYMBOL_ROWS).joinToString("").toSet() +
            setOf(',', '.')
        for (base in Layouts.SYMBOL_ALTERNATES.keys) {
            assertTrue(base in available, "no symbol page has the key '$base'")
        }
        val digits = Layouts.SYMBOL_ROWS[0].toSet()
        for (base in Layouts.DIGIT_ALTERNATES.keys) {
            assertTrue(base in digits, "no number row has the key '$base'")
        }
    }

    /**
     * Every alternate has to stay reachable. The popup gives each entry 44dp and prepends
     * the key's own character, so seven alternates already fill a small phone's width.
     */
    @Test
    fun `no alternate list is longer than the popup can show`() {
        val lists = Layouts.LATIN_ALTERNATES.values + Layouts.SYMBOL_ALTERNATES.values +
            Layouts.DIGIT_ALTERNATES.values + KeyboardLayouts.ALL.flatMap { it.alternates.values }
        for (alternates in lists) {
            assertTrue(alternates.length <= 7, "'$alternates' is too long to fit on screen")
        }
    }

    /** #555: every precomposed fraction is on the number row exactly once. */
    @Test
    fun `digit alternates offer every vulgar fraction once`() {
        val fractions = "½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅐⅛⅜⅝⅞⅑⅒"
        val offered = Layouts.DIGIT_ALTERNATES.values.joinToString("").filter { it in fractions }
        assertEquals(fractions.toSet(), offered.toSet())
        assertEquals(fractions.length, offered.length, "a fraction is offered on two keys")
        // The commonest stay first on the 1 key, so its corner hint is unchanged.
        assertTrue(Layouts.DIGIT_ALTERNATES.getValue('1').startsWith("¹½⅓¼"))
    }

    /**
     * The corner preview exists to show the user something they could not guess, so an
     * accented form of the key's own letter is not worth printing — those keep the dot.
     */
    @Test
    fun `the corner preview skips accents and anything already on the key`() {
        assertEquals(null, Layouts.alternatePreview("éèêëēėę", "e"))
        assertEquals(null, Layouts.alternatePreview("ÉÈÊË", "E"))
        assertEquals("¡", Layouts.alternatePreview("¡", "!"))
        assertEquals("<", Layouts.alternatePreview("<[{", "("))
        assertEquals("…", Layouts.alternatePreview("…", "."))
        // Distinct letters are worth showing; a smaller copy of the digit itself is not.
        assertEquals("Ł", Layouts.alternatePreview("Ł", "L"))
        assertEquals("½", Layouts.alternatePreview("¹½⅓¼⅛", "1"))
        assertEquals(null, Layouts.alternatePreview("⁴", "4"))
        // The digit is already drawn in the opposite corner; the accents behind it are not
        // worth printing either, so this key falls back to the dot.
        assertEquals(null, Layouts.alternatePreview("3éèêë", "e", skip = "3"))
        assertEquals(null, Layouts.alternatePreview("", "q"))
    }

    @Test
    fun `only English layouts claim the English dictionary`() {
        for (layout in KeyboardLayouts.ALL.filter { it.englishDictionary }) {
            assertTrue(layout.id.startsWith("en_"), "${layout.id} claims the English dictionary")
        }
    }

    @Test
    fun `charAt applies case for Latin and the shift layer for Devanagari`() {
        val english = assertNotNull(KeyboardLayouts.byId("en_qwerty"))
        assertEquals("q", english.charAt(0, 0, shifted = false))
        assertEquals("Q", english.charAt(0, 0, shifted = true))
        assertTrue(english.cased)

        val hindi = assertNotNull(KeyboardLayouts.byId("hi_inscript"))
        assertEquals("ब", hindi.charAt(0, 5, shifted = false))
        assertEquals("भ", hindi.charAt(0, 5, shifted = true))
        // Devanagari has no case, so auto-capitalize must not flip it to the shift layer.
        assertFalse(hindi.cased)
        assertTrue(hindi.hasShift)
    }

    /** Arabic, Hebrew and Persian have neither case nor a second layer; the shift key is dropped. */
    @Test
    fun `caseless scripts report no shift`() {
        for (id in listOf("ar", "he", "fa")) {
            val layout = assertNotNull(KeyboardLayouts.byId(id))
            assertFalse(layout.hasShift, "$id should have no shift key")
            assertFalse(layout.cased)
        }
    }

    /**
     * Composed scripts must not auto-capitalize: their shift key selects a second character
     * layer (katakana, tense consonants), so "capitalizing" would change what is typed.
     */
    @Test
    fun `composed layouts do not auto-capitalize`() {
        for (layout in KeyboardLayouts.ALL.filter { it.composer != null }) {
            assertFalse(layout.cased, "${layout.id} would auto-capitalize")
        }
    }

    @Test
    fun `the composed scripts are all present`() {
        val byComposer = KeyboardLayouts.ALL.mapNotNull { it.composer }.toSet()
        assertEquals(ComposerKind.entries.toSet(), byComposer, "an engine has no layout")
    }

    /** Only the Chinese engines put anything in the strip; the others compose silently. */
    @Test
    fun `candidate layouts are the Chinese ones`() {
        val offering = KeyboardLayouts.ALL.filter { it.offersCandidates }.map { it.id }
        assertEquals(listOf("zh_pinyin", "zh_pinyin_tc", "zh_bopomofo"), offering)
    }

    @Test
    fun `active layout resolves from the catalog and falls back for unknown ids`() {
        assertEquals("ru", KeyboardSettings(activeLayoutId = "ru").activeLayout.id)
        assertEquals(
            KeyboardLayouts.DEFAULT.id,
            KeyboardSettings(activeLayoutId = "nonsense").activeLayout.id,
        )
    }
}
