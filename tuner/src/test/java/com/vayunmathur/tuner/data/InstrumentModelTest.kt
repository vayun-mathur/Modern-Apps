package com.vayunmathur.tuner.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Instrument catalogue model: pitch ordering, sounding range, parsing. */
class InstrumentModelTest {

    private val guitar = Instrument(
        id = "guitar", familyId = "guitar", kind = InstrumentKind.FRETTED,
        strings = listOf(
            StringSpec(40, "E"), StringSpec(45, "A"), StringSpec(50, "D"),
            StringSpec(55, "G"), StringSpec(59, "B"), StringSpec(64, "E"),
        ),
        fretCount = 12, analysis = AnalysisRange(80.0, 1200.0),
    )
    private val uke = Instrument(
        id = "uke", familyId = "ukulele", kind = InstrumentKind.FRETTED,
        strings = listOf(
            StringSpec(67, "G"), StringSpec(60, "C"), StringSpec(64, "E"), StringSpec(69, "A"),
        ),
        fretCount = 12, analysis = AnalysisRange(200.0, 1200.0),
    )
    private val piano = Instrument(
        id = "piano", familyId = "piano", kind = InstrumentKind.KEYBOARD,
        keyboard = KeyboardSpec(21, 108), analysis = AnalysisRange(27.0, 4200.0),
    )

    @Test
    fun tuningLabelAndPolyphony() {
        assertEquals("E A D G B E", guitar.tuningLabel)
        assertEquals(6, guitar.polyphony)
        assertEquals(10, piano.polyphony)
    }

    @Test
    fun stringsByPitchOrdersBySoundingPitchAndSkipsMuted() {
        // Standard guitar is already low-to-high in string order.
        assertEquals(listOf(0, 1, 2, 3, 4, 5), guitar.stringsByPitch(listOf(0, 0, 0, 0, 0, 0)))
        // Re-entrant ukulele: G(67) C(60) E(64) A(69) -> pitch order C,E,G,A = indices 1,2,0,3.
        assertEquals(listOf(1, 2, 0, 3), uke.stringsByPitch(listOf(0, 0, 0, 0)))
        // Muted strings (-1) are excluded; fretting raises pitch.
        assertEquals(listOf(1, 2), uke.stringsByPitch(listOf(-1, 0, 0, -1)))
        assertEquals(listOf(1, 0), guitar.stringsByPitch(listOf(12, 0, -1, -1, -1, -1)))
    }

    @Test
    fun soundingRangeFrettedKeyboardAndUnfretted() {
        assertEquals(40..76, guitar.soundingRange)
        assertEquals(21..108, piano.soundingRange)
        val violin = Instrument(
            id = "violin", familyId = "violin", kind = InstrumentKind.UNFRETTED,
            strings = listOf(StringSpec(55, "G"), StringSpec(62, "D")),
            analysis = AnalysisRange(196.0, 3000.0),
        )
        assertEquals(55, violin.soundingRange.first)
        assertTrue(violin.soundingRange.last > 55)
    }

    @Test
    fun analysisRangeMidiConversionRoundsInwards() {
        val range = AnalysisRange(440.0, 440.0)
        assertEquals(69..69, range.midiRange)
        assertTrue(AnalysisRange(80.0, 1200.0).midiRange.first >= 39)
    }

    @Test
    fun catalogByIdFallbackAndParse() {
        val catalog = InstrumentCatalog(listOf(guitar, uke))
        assertEquals(uke, catalog.byId("uke"))
        assertEquals(guitar, catalog.byId("missing"))
        val json = """{"instruments":[{"id":"x","familyId":"guitar","kind":"FRETTED","strings":[{"openMidi":40,"label":"E"}],"fretCount":12,"analysis":{"minHz":80.0,"maxHz":1200.0}}]}"""
        val parsed = InstrumentCatalog.parse(json)
        assertEquals(1, parsed.instruments.size)
        assertEquals("x", parsed.instruments[0].id)
        assertEquals(40, parsed.instruments[0].strings[0].openMidi)
    }
}
