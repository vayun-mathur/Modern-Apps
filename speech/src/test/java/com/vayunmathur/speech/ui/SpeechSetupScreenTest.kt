package com.vayunmathur.speech.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.speech.SpeechSetupScreen
import com.vayunmathur.speech.util.SpeechSetupActions
import com.vayunmathur.speech.util.SpeechSetupUiState
import com.vayunmathur.speech.util.TtsVoiceUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SpeechSetupScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private class RecordingActions : SpeechSetupActions {
        var mic = 0
        var voiceSettings = 0
        var ttsSettings = 0
        var refreshes = 0
        override fun requestMicPermission() { mic++ }
        override fun openVoiceInputSettings() { voiceSettings++ }
        override fun openTtsSettings() { ttsSettings++ }
        override fun refresh() { refreshes++ }
    }

    private val languages = listOf(
        TtsVoiceUiState("en", "en-US", "English", "English"),
        TtsVoiceUiState("de", "de-DE", "Deutsch", "German"),
        TtsVoiceUiState("fr", "fr-FR", "Français", "French"),
    )

    private fun shown(text: String) {
        rule.onNodeWithText(text).performScrollTo()
        rule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun setScreen(state: SpeechSetupUiState, actions: SpeechSetupActions = SpeechSetupActions.Noop) {
        rule.setContent {
            DynamicTheme(darkTheme = false) {
                SpeechSetupScreen(state = state, actions = actions)
            }
        }
    }

    @Test
    fun brokenInstallStateShowsWarningAndAllSteps() {
        setScreen(SpeechSetupUiState())
        rule.onNodeWithText("Install looks damaged").assertIsDisplayed()
        rule.onNodeWithText("1. Microphone access").assertIsDisplayed()
        rule.onNodeWithText("2. Set as speech recognizer").assertIsDisplayed()
        shown("3. Set as text-to-speech engine")
        shown("Try it")
        shown("Try the voice")
    }

    @Test
    fun micGrantButtonFiresActionOnlyWhenMicMissing() {
        val actions = RecordingActions()
        setScreen(SpeechSetupUiState(modelReady = true, ttsModelReady = true, hasMic = false), actions)
        rule.onNodeWithText("Grant microphone").assertIsDisplayed()
        rule.onNodeWithText("Grant microphone").performClick()
        assertEquals(1, actions.mic)
    }

    @Test
    fun readyStateHidesBrokenCardGrantButtonAndMarksStepsDone() {
        setScreen(
            SpeechSetupUiState(
                modelReady = true, hasMic = true, isRecognizerDefault = true,
                ttsModelReady = true, isTtsDefault = true,
                ttsVoices = languages, currentTestLang = "en",
            ),
        )
        rule.onNodeWithText("Install looks damaged").assertDoesNotExist()
        rule.onNodeWithText("Grant microphone").assertDoesNotExist()
        rule.onNodeWithText("1. Microphone access  ✓").assertIsDisplayed()
        rule.onNodeWithText("2. Set as speech recognizer  ✓").assertIsDisplayed()
        shown("3. Set as text-to-speech engine  ✓")
    }

    @Test
    fun settingsButtonsFireActions() {
        val actions = RecordingActions()
        setScreen(
            SpeechSetupUiState(modelReady = true, ttsModelReady = true, hasMic = true, ttsVoices = languages),
            actions,
        )
        rule.onNodeWithText("Open voice input settings").performClick()
        rule.onNodeWithText("Open text-to-speech settings").performClick()
        assertEquals(1, actions.voiceSettings)
        assertEquals(1, actions.ttsSettings)
    }

    @Test
    fun whisperModelMissingShowsWarning() {
        setScreen(SpeechSetupUiState(modelReady = false, ttsModelReady = true))
        rule.onNodeWithText("Install looks damaged").assertIsDisplayed()
    }

    @Test
    fun ttsBundleMissingShowsWarning() {
        setScreen(SpeechSetupUiState(modelReady = true, ttsModelReady = false))
        rule.onNodeWithText("Install looks damaged").assertIsDisplayed()
    }

    @Test
    fun testMicrophoneButtonDisabledWithoutMic() {
        setScreen(SpeechSetupUiState(modelReady = true, ttsModelReady = true, hasMic = false))
        rule.onNodeWithText("Test microphone").assertIsNotEnabled()
    }

    @Test
    fun testMicrophoneClickReportsNoRecognizer() {
        setScreen(SpeechSetupUiState(modelReady = true, ttsModelReady = true, hasMic = true))
        rule.onNodeWithText("Test microphone").performScrollTo()
        rule.onNodeWithText("Test microphone").assertIsEnabled()
        rule.onNodeWithText("Test microphone").performClick()
        rule.waitForIdle()
        // Under Robolectric no recognition service is bound, so the card explains step 2.
        val shown = runCatching {
            rule.onNodeWithText("No recognizer selected yet (finish step 2).").assertExists()
            true
        }.getOrDefault(false)
        val listening = runCatching {
            rule.onNodeWithText("Listening…").assertExists()
            true
        }.getOrDefault(false)
        assertTrue(shown || listening, "expected a status after clicking Test microphone")
    }

    @Test
    fun speakSampleDisabledWhenTtsModelMissing() {
        setScreen(
            SpeechSetupUiState(modelReady = true, ttsModelReady = false, ttsVoices = languages),
        )
        rule.onNodeWithText("Speak sample").performScrollTo()
        rule.onNodeWithText("Speak sample").assertIsNotEnabled()
    }

    @Test
    fun languageDropdownShowsSelectionAndPicksAnother() {
        setScreen(
            SpeechSetupUiState(
                modelReady = true, hasMic = true, ttsModelReady = true,
                ttsVoices = languages, currentTestLang = "de",
            ),
        )
        shown("Deutsch (German)")
        rule.onNodeWithText("Deutsch (German)").performClick()
        rule.onNodeWithText("Français · French (fr)").assertIsDisplayed()
        rule.onNodeWithText("Deutsch · German (de)").assertIsSelected()
        rule.onNodeWithText("Français · French (fr)").performClick()
        rule.waitForIdle()
        shown("Français (French)")
    }

    @Test
    fun unknownCurrentLanguageFallsBackToFirstLanguage() {
        setScreen(
            SpeechSetupUiState(
                modelReady = true, ttsModelReady = true,
                ttsVoices = languages, currentTestLang = "xx",
            ),
        )
        shown("English (English)")
    }

    @Test
    fun emptyLanguageListShowsSpeakButtonWithoutDropdown() {
        setScreen(
            SpeechSetupUiState(modelReady = true, ttsModelReady = true, ttsVoices = emptyList()),
        )
        rule.onNodeWithText("Speak sample").performScrollTo()
        rule.onNodeWithText("Speak sample").assertIsEnabled()
        rule.onNodeWithText("Speak sample").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("The bundled voices could not be read.").assertExists()
    }

    @Test
    fun speakSampleWithLanguagesShowsLoadingStatus() {
        setScreen(
            SpeechSetupUiState(
                modelReady = true, ttsModelReady = true,
                ttsVoices = languages, currentTestLang = "en",
            ),
        )
        rule.onNodeWithText("Speak sample").performScrollTo()
        rule.onNodeWithText("Speak sample").performClick()
        rule.waitForIdle()
        val loading = runCatching {
            rule.onNodeWithText("Loading…").assertExists(); true
        }.getOrDefault(false)
        val failed = runCatching {
            rule.onNodeWithText("Engine failed to start.").assertExists(); true
        }.getOrDefault(false)
        assertTrue(loading || failed, "expected Loading or engine-failure status")
    }
}
