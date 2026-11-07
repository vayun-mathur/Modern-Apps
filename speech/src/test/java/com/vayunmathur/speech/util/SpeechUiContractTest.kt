package com.vayunmathur.speech.util

import com.vayunmathur.speech.domain.SupertonicVoices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpeechUiContractTest {

    @Test
    fun setupStateDefaultsAreAllUndone() {
        val state = SpeechSetupUiState()
        assertFalse(state.modelReady)
        assertFalse(state.hasMic)
        assertFalse(state.isRecognizerDefault)
        assertFalse(state.ttsModelReady)
        assertFalse(state.isTtsDefault)
        assertEquals(emptyList(), state.ttsVoices)
        assertEquals("en", state.currentTestLang)
    }

    @Test
    fun setupStateCopyAndEquality() {
        val voices = listOf(TtsVoiceUiState("de", "de-DE", "Deutsch", "German"))
        val state = SpeechSetupUiState(modelReady = true, ttsVoices = voices, currentTestLang = "de")
        assertEquals(state, state.copy())
        assertNotEquals(state, state.copy(hasMic = true))
        assertEquals(voices, state.copy().ttsVoices)
        assertTrue(state.modelReady)
    }

    @Test
    fun voiceUiStateFieldsAndEquality() {
        val voice = TtsVoiceUiState(code = "fr", bcp47 = "fr-FR", nativeName = "Français", englishName = "French")
        assertEquals("fr", voice.code)
        assertEquals("fr-FR", voice.bcp47)
        assertEquals("Français", voice.nativeName)
        assertEquals("French", voice.englishName)
        assertEquals(voice, voice.copy())
        assertNotEquals(voice, voice.copy(code = "de"))
        val (code, bcp47, native, english) = voice
        assertEquals(listOf("fr", "fr-FR", "Français", "French"), listOf(code, bcp47, native, english))
    }

    @Test
    fun voicesFromCatalogMapToUiState() {
        val ui = SupertonicVoices.ALL.map {
            TtsVoiceUiState(it.code, it.bcp47, it.nativeName, it.englishName)
        }
        assertEquals(31, ui.size)
        assertEquals(TtsVoiceUiState("en", "en-US", "English", "English"), ui.first())
        assertEquals("vi", ui.last().code)
    }

    @Test
    fun noopActionsDoNothingAndDoNotThrow() {
        val actions: SpeechSetupActions = SpeechSetupActions.Noop
        actions.requestMicPermission()
        actions.openVoiceInputSettings()
        actions.openTtsSettings()
        actions.refresh()
        // Reaching here without throwing is the contract previews rely on.
    }

    @Test
    fun defaultInterfaceMethodsDoNothing() {
        val actions = object : SpeechSetupActions {}
        actions.requestMicPermission()
        actions.openVoiceInputSettings()
        actions.openTtsSettings()
        actions.refresh()
    }
}
