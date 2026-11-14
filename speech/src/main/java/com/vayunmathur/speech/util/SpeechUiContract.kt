package com.vayunmathur.speech.util

/**
 * The UI contract for the setup screen.
 *
 * The screen takes a state value plus an actions interface rather than reading the device
 * directly, so it can be rendered by a `@Preview` — which is what the store listing images
 * are generated from. It lives in `util` so the dependency runs one way: the UI depends on
 * `util`, never the reverse.
 *
 * There is no ViewModel here. Every field below is a fact about the device (a granted
 * permission, a Settings.Secure entry, a bundled model) that the binder re-reads when
 * the user returns from a settings screen, so the state is assembled at the call site.
 *
 * # What bundling the voices removed
 *
 * This interface had ten members and the state had eight fields, most of them about downloads:
 * per-voice progress, a delete, an installed-codes list, a total byte count. Supertonic ships in
 * the APK, so there is nothing to download, nothing to delete and no progress to poll — the TTS
 * section of the screen went from a list of 42 download rows to a sentence.
 */

/** One language the engine can speak, for the read-only list in the voices section. */
data class TtsVoiceUiState(
    val code: String,
    val bcp47: String,
    val nativeName: String,
    val englishName: String,
)

/** Which of the five setup steps are already done, plus the languages to list. */
data class SpeechSetupUiState(
    /** Whisper recognition model readable from the APK's assets. */
    val modelReady: Boolean = false,
    val hasMic: Boolean = false,
    /** This app is the device's `voice_recognition_service`. */
    val isRecognizerDefault: Boolean = false,
    /** The Supertonic bundle is readable from the APK's assets. */
    val ttsModelReady: Boolean = false,
    /** This app is the device's `tts_default_synth`. */
    val isTtsDefault: Boolean = false,

    /** Every language the bundle covers. Not a per-install fact any more, but still previewable. */
    val ttsVoices: List<TtsVoiceUiState> = emptyList(),
    /** The language the "speak a sample" button reads in. */
    val currentTestLang: String = "en",
)

/**
 * Setup screen callbacks. Every method has a no-op default so a preview can render the
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 *
 * Neither model is downloaded: recognition and synthesis both ship in the APK, so nothing here is
 * `suspend` and nothing reports progress.
 */
interface SpeechSetupActions {
    fun requestMicPermission() {}
    fun openVoiceInputSettings() {}
    fun openTtsSettings() {}

    /** Re-read the device state after a step completes. */
    fun refresh() {}

    /** Shared constants and helpers for SpeechSetupActions. */
    companion object {
        val Noop: SpeechSetupActions = object : SpeechSetupActions {}
    }
}
