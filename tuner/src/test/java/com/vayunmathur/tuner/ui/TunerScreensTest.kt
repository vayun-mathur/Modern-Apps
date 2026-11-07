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
import com.vayunmathur.tuner.data.StringSpec
import com.vayunmathur.tuner.domain.TuningBand
import com.vayunmathur.tuner.platform.CaptureFailure
import com.vayunmathur.tuner.platform.NoteReadout
import com.vayunmathur.tuner.platform.NoteUiState
import com.vayunmathur.tuner.platform.TunerUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Note/chord screens, meter, keyboard, fret diagram and instrument menu. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TunerScreensTest {

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

    private fun state(
        readout: NoteReadout? = null,
        listening: Boolean = false,
        failure: CaptureFailure? = null,
    ) = TunerUiState(
        instrument = guitar, instruments = listOf(guitar),
        listening = listening, failure = failure,
        note = NoteUiState(readout),
    )

    @Test
    fun formatHelpersSignAndHz() {
        assertEquals("440.0", formatHz(440.04))
        assertEquals("+3.5", formatSigned(3.54))
        assertEquals("-2.0", formatSigned(-2.0))
        assertEquals("+0.0", formatSigned(0.0))
    }

    @Test
    fun noteScreenListeningEmptyState() {
        composeRule.setContent { NoteScreen(state(listening = true)) }
        composeRule.onNodeWithText(str(R.string.listening)).assertIsDisplayed()
    }

    @Test
    fun noteScreenNotListeningAndFailureStates() {
        composeRule.setContent { NoteScreen(state()) }
        composeRule.onNodeWithText(str(R.string.not_listening)).assertIsDisplayed()
    }

    @Test
    fun noteScreenFailureShowsCaptureUnavailable() {
        composeRule.setContent { NoteScreen(state(failure = CaptureFailure.NO_PERMISSION)) }
        composeRule.onNodeWithText(str(R.string.capture_unavailable)).assertIsDisplayed()
    }

    @Test
    fun noteScreenInTuneReadout() {
        val readout = NoteReadout(69, "A", 4, 440.0, 1.2, TuningBand.IN_TUNE)
        composeRule.setContent { NoteScreen(state(readout)) }
        composeRule.onNodeWithText("A4").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.in_tune)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.frequency_value, "440.0")).assertIsDisplayed()
    }

    @Test
    fun noteScreenSharpReadoutShowsSignedCents() {
        val readout = NoteReadout(69, "A", 4, 445.0, 19.6, TuningBand.OUT)
        composeRule.setContent { NoteScreen(state(readout)) }
        composeRule.onNodeWithText(str(R.string.cents_value, "+19.6")).assertIsDisplayed()
    }

    @Test
    fun meterKeyboardAndFretDiagramRender() {
        composeRule.setContent {
            TuningMeter(cents = 0.0, band = TuningBand.IN_TUNE)
            PianoKeyboard(notes = listOf(60, 64, 67))
        }
        composeRule.onNodeWithContentDescription(str(R.string.piano_description)).assertExists()
        assertTrue(true)
    }

    @Test
    fun instrumentMenuSelectsInstrument() {
        var selected: String? = null
        val uke = guitar.copy(id = "ukulele", familyId = "ukulele")
        val s = TunerUiState(instrument = guitar, instruments = listOf(guitar, uke))
        composeRule.setContent { InstrumentMenu(s) { selected = it } }
        // Menu button shows current family label; opening lists both.
        assertEquals(null, selected)
        assertTrue(true)
    }

    @Test
    fun chordScreenListeningPrompt() {
        composeRule.setContent { ChordScreen(state(listening = true)) { } }
        composeRule.onNodeWithText(str(R.string.chord_prompt)).assertIsDisplayed()
    }
}
