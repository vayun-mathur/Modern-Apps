package com.vayunmathur.tuner.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.tuner.R
import com.vayunmathur.tuner.data.AnalysisRange
import com.vayunmathur.tuner.data.Instrument
import com.vayunmathur.tuner.data.InstrumentKind
import com.vayunmathur.tuner.data.KeyboardSpec
import com.vayunmathur.tuner.data.StringSpec
import com.vayunmathur.tuner.domain.ChordLabel
import com.vayunmathur.tuner.domain.ChordName
import com.vayunmathur.tuner.domain.ChordPresentation
import com.vayunmathur.tuner.domain.ChordQuality
import com.vayunmathur.tuner.domain.ChordReading
import com.vayunmathur.tuner.domain.ChordTier
import com.vayunmathur.tuner.domain.DetectedNote
import com.vayunmathur.tuner.domain.Voicing
import com.vayunmathur.tuner.platform.ChordUiState
import com.vayunmathur.tuner.platform.TunerUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chord screen readings (confident/ambiguous/uncertain/single) and diagrams. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TunerChordDiagramTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg a: Any): String = context.getString(id, *a)

    private val guitar = Instrument(
        id = "guitar", familyId = "guitar", kind = InstrumentKind.FRETTED,
        strings = listOf(
            StringSpec(40, "E"), StringSpec(45, "A"), StringSpec(50, "D"),
            StringSpec(55, "G"), StringSpec(59, "B"), StringSpec(64, "E"),
        ),
        fretCount = 12, analysis = AnalysisRange(80.0, 1200.0),
    )
    private val piano = Instrument(
        id = "piano", familyId = "piano", kind = InstrumentKind.KEYBOARD,
        keyboard = KeyboardSpec(21, 108), analysis = AnalysisRange(27.0, 4200.0),
    )

    private fun reading(label: ChordLabel, presentation: ChordPresentation, tier: ChordTier = ChordTier.CONFIRMED) =
        ChordReading(
            notes = listOf(DetectedNote(60, 1.0), DetectedNote(64, 0.9), DetectedNote(67, 0.8)),
            bass = 48, bassConfidence = 0.9f, label = label, alternates = emptyList(),
            confidence = 0.95f, tier = tier, presentation = presentation,
        )

    private fun chordState(
        instrument: Instrument,
        label: ChordLabel,
        presentation: ChordPresentation,
        tier: ChordTier = ChordTier.CONFIRMED,
    ) = TunerUiState(
        instrument = instrument, instruments = listOf(guitar, piano),
        chord = ChordUiState(
            reading = reading(label, presentation, tier),
            voicings = listOf(Voicing(listOf(-1, 3, 2, 0, 1, 0), null, 3, listOf(48, 53, 57, 60, 64), 1.0, true)),
        ),
    )

    @Test
    fun chordTextSymbolsAndSlashBass() {
        assertEquals("C", chordText(ChordName(0, ChordQuality.MAJOR)))
        assertEquals("Am", chordText(ChordName(9, ChordQuality.MINOR)))
        assertTrue(chordText(ChordName(0, ChordQuality.MAJOR, bass = 4)).startsWith("C/"))
    }

    @Test
    fun confidentChordShowsNameNotesAndShapes() {
        val s = chordState(guitar, ChordLabel.Chord(ChordName(0, ChordQuality.MAJOR)), ChordPresentation.CONFIDENT)
        composeRule.setContent { ChordScreen(s) {} }
        composeRule.onNodeWithText("C").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.notes_detected)).assertIsDisplayed()
    }

    @Test
    fun singleNoteAndIntervalHeadlines() {
        val single = chordState(piano, ChordLabel.SingleNote(60), ChordPresentation.CONFIDENT)
        composeRule.setContent { ChordScreen(single) {} }
        composeRule.onNodeWithText(str(R.string.notes_detected)).assertIsDisplayed()
    }

    @Test
    fun uncertainChordShowsConfidenceCaveat() {
        val s = chordState(guitar, ChordLabel.Chord(ChordName(0, ChordQuality.MAJOR)), ChordPresentation.UNCERTAIN)
        composeRule.setContent { ChordScreen(s) {} }
        composeRule.onNodeWithText(str(R.string.confidence_low)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.confidence_note)).assertIsDisplayed()
    }

    @Test
    fun provisionalTierShowsSettlingNote() {
        val s = chordState(
            guitar, ChordLabel.Chord(ChordName(0, ChordQuality.MAJOR)),
            ChordPresentation.CONFIDENT, tier = ChordTier.PROVISIONAL,
        )
        composeRule.setContent { ChordScreen(s) {} }
        composeRule.onNodeWithText(str(R.string.confidence_settling)).assertIsDisplayed()
    }

    @Test
    fun ambiguousChordOffersAlternateSwitch() {
        val label = ChordLabel.Ambiguous(ChordName(9, ChordQuality.MINOR_SEVENTH), ChordName(0, ChordQuality.SIXTH))
        val s = chordState(guitar, label, ChordPresentation.AMBIGUOUS)
        composeRule.setContent { ChordScreen(s) {} }
        composeRule.onNodeWithText(str(R.string.chord_ambiguous_note)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.chord_show_other, chordText(label.secondary)), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun notesOnlyPresentationWithholdsName() {
        val s = chordState(piano, ChordLabel.Silent, ChordPresentation.NOTES_ONLY)
        composeRule.setContent { ChordScreen(s) {} }
        composeRule.onNodeWithText(str(R.string.chord_unsure)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.notes_detected)).assertIsDisplayed()
    }

    @Test
    fun fretDiagramRendersForVoicing() {
        val voicing = Voicing(listOf(0, 2, 2, 1, 0, 0), null, 3, listOf(40, 47, 52, 56, 59, 64), 1.0, true)
        composeRule.setContent { FretDiagram(guitar, voicing) }
        composeRule.onNodeWithContentDescription(str(R.string.diagram_description)).assertExists()
    }
}
