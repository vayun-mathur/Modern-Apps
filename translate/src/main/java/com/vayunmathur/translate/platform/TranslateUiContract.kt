package com.vayunmathur.translate.platform

import com.vayunmathur.translate.data.TranslationRecord
import com.vayunmathur.translate.domain.Language
import com.vayunmathur.translate.domain.Languages

/**
 * The UI contract between [TranslateViewModel] (plus the speech plumbing that wraps it) and
 * the text translation screen.
 *
 * The screens take a state value plus an actions interface rather than the ViewModel itself,
 * so they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, never the reverse.
 *
 * The actions are not implemented by [TranslateViewModel] directly: half of the interface
 * is screen-local (what the mic is doing) and half needs an activity result launcher, so the
 * binder composable assembles them.
 *
 * The camera screen is deliberately absent. It is built around a CameraX viewfinder, which
 * Layoutlib cannot render, so there is nothing a preview could usefully capture and no
 * reason to carry a contract for it.
 */

/**
 * Mic button phases. TRANSCRIBING exists because the offline recognizer keeps working for
 * a moment after the mic stops; showing it (and ignoring taps) avoids the "stuck on Stop"
 * / double-start / "recognizer busy" confusion.
 */
enum class MicState { IDLE, LISTENING, TRANSCRIBING }

/** Everything the text translation screen draws. */
data class TextTranslateUiState(
    val sourceLang: String = Languages.AUTO.code,
    val targetLang: String = "es",
    /** False only while the NLLB engine is still loading; the model itself is pre-installed. */
    val translationAvailable: Boolean = false,
    val inputText: String = "",
    val outputText: String = "",
    val isTranslating: Boolean = false,
    /** True when the current input + pair is already saved as a favorite. */
    val isFavorite: Boolean = false,
    /** Only true once the displayed result has a persisted history row. */
    val canSave: Boolean = false,
    val micState: MicState = MicState.IDLE,
    val speechError: String? = null,
)

/**
 * Text screen callbacks. Every method has a no-op default so a preview can render the
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface TextTranslateActions {
    fun setSource(code: String) {}
    fun setTarget(code: String) {}
    fun swap() {}
    fun setInput(text: String) {}

    /** The mic FAB: start listening, stop, or — while transcribing — nothing. */
    fun toggleMic() {}

    fun copyOutput() {}
    fun speakOutput() {}
    fun toggleFavorite() {}
    fun openCamera() {}
    fun openImage() {}
    fun openHistory() {}
    fun openLanguagePicker(forSource: Boolean) {}

    companion object {
        val Noop: TextTranslateActions = object : TextTranslateActions {}
    }
}

/** Everything the language picker screen draws, as literal values for previews. */
data class LanguagePickerUiState(
    /** True when picking the source language (shows the pinned Auto row); else the target. */
    val forSource: Boolean = true,
    val selectedCode: String = Languages.AUTO.code,
    val recents: List<Language> = emptyList(),
    val query: String = "",
)

/**
 * Picker callbacks. Every method has a no-op default so a preview can render the
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface LanguagePickerActions {
    fun select(code: String) {}
    fun setQuery(query: String) {}
    fun goBack() {}

    companion object {
        val Noop: LanguagePickerActions = object : LanguagePickerActions {}
    }
}

/** Everything the history screen draws, as literal values for previews. */
data class HistoryUiState(
    val records: List<TranslationRecord> = emptyList(),
)

/**
 * History callbacks. Every method has a no-op default so a preview can render the
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface HistoryActions {
    fun toggleFavorite(id: Long, favorite: Boolean) {}
    fun delete(record: TranslationRecord) {}
    fun clearHistory() {}
    fun reuse(record: TranslationRecord) {}
    fun goBack() {}

    companion object {
        val Noop: HistoryActions = object : HistoryActions {}
    }
}
