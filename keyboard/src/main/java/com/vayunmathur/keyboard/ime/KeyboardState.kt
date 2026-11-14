package com.vayunmathur.keyboard.ime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.keyboard.platform.VoiceFailure
import com.vayunmathur.keyboard.util.ClipItem
import com.vayunmathur.keyboard.util.EmojiData
import com.vayunmathur.keyboard.util.KeyboardLayout
import com.vayunmathur.keyboard.util.KeyboardLayouts
import com.vayunmathur.keyboard.util.KeyboardPage
import com.vayunmathur.keyboard.util.KeyboardSettings
import com.vayunmathur.keyboard.util.ShiftState

/** What the Enter key should do, derived from the target field's [android.view.inputmethod.EditorInfo]. */
enum class EnterAction { RETURN, GO, SEARCH, SEND, NEXT, DONE, PREVIOUS }

/**
 * Text-field flavour that tweaks the letters layout: EMAIL surfaces `@`, URL surfaces
 * `/` (both in place of the comma key), PERSON_NAME capitalizes each word,
 * NO_SUGGESTIONS turns composing off for fields autocorrect would corrupt (phonetic).
 * Everything else keeps punctuation. Number/phone/date-time fields use the dedicated
 * numeric page instead (see [KeyboardState.basePage]).
 */
enum class TextVariation { NORMAL, EMAIL, URL, PERSON_NAME, NO_SUGGESTIONS }

/**
 * Which separators the numeric page offers: NUMBER is the calculator-style pad (its
 * `.` `,` `+` `-` already serve every number/decimal/signed combination), DATE swaps
 * in `/`, TIME swaps in `:`, DATE_TIME offers both. Covers Compose Number through
 * DecimalPasswordSigned (same pad, no suggestions) and Date/DateTime/Time.
 */
enum class NumericMode { NUMBER, DATE, TIME, DATE_TIME }

/**
 * What voice input is doing, or null when it is idle. An IME has no window of its own to put
 * a dialog in, so dictation reports itself in the strip above the keys.
 */
sealed interface VoiceState {
    /** Capturing audio; [partial] is the best transcript so far, which may be blank. */
    data class Listening(val partial: String) : VoiceState

    /** Capture is over and the recognizer is still working on it. */
    data object Transcribing : VoiceState

    /** The session ended with no text to commit. */
    data class Failed(val failure: VoiceFailure) : VoiceState
}

/**
 * Compose-observable UI state owned by the IME service. The service mutates these fields in
 * response to key actions and editor changes; [com.vayunmathur.keyboard.ui.KeyboardScreen]
 * reads them to render. Kept as a plain holder (not a ViewModel) since the service owns its
 * whole lifetime.
 */
class KeyboardState {
    /** Currently visible page of keys. */
    var page by mutableStateOf(KeyboardPage.LETTERS)

    /** The non-emoji page to return to (letters normally, numeric for number fields). */
    var basePage by mutableStateOf(KeyboardPage.LETTERS)

    var shift by mutableStateOf(ShiftState.OFF)

    /** Up to three completion/correction candidates for the word being typed. */
    var suggestions by mutableStateOf<List<String>>(emptyList())

    var settings by mutableStateOf(KeyboardSettings())

    var enterAction by mutableStateOf(EnterAction.RETURN)

    /**
     * App-supplied label for a custom IME action ([android.view.inputmethod.EditorInfo.actionLabel]),
     * or null when the action is one of the standard [EnterAction] values. Takes precedence over
     * [enterAction] when labelling the enter key.
     */
    var enterActionLabel by mutableStateOf<String?>(null)

    /** True when the target field is a password field (disables suggestions/composing). */
    var passwordField by mutableStateOf(false)

    /** Field flavour that adapts the letters layout (email/url punctuation, name caps). */
    var textVariation by mutableStateOf(TextVariation.NORMAL)

    /** Separator set for the numeric page (date/time fields swap in `/` and `:`). */
    var numericMode by mutableStateOf(NumericMode.NUMBER)

    /**
     * True for ASCII-only fields (Compose Ascii and PasswordVisible, both of which arrive
     * as visible-password). While set the letters page renders a Latin layout regardless
     * of the user's active layout; the setting itself is untouched and restored after.
     */
    var forceAscii by mutableStateOf(false)

    /**
     * The letter layout actually drawn: the user's layout, except ASCII-forced fields
     * fall back to English QWERTY unless the active layout is already plain Latin.
     * Composing layouts are excluded even when their rows are Latin (romaji, pinyin):
     * their engines output kana/hanzi, which an ASCII-only field cannot take.
     */
    val effectiveLayout: KeyboardLayout
        get() {
            val active = settings.activeLayout
            if (!forceAscii) return active
            if (active.isLatinScript && active.composer == null) return active
            return KeyboardLayouts.byId("en_qwerty") ?: active
        }

    /**
     * Navigation-bar height in pixels, read from the window by the service. In an IME
     * the window insets are not reliably dispatched to Compose, so we pad the keyboard
     * bottom by this value ourselves to keep the last row clear of the system bar.
     */
    var bottomInsetPx by mutableStateOf(0)

    /** The emoji palette, replaced by the generated asset once the service has read it. */
    var emojiData by mutableStateOf(EmojiData.BUILTIN)

    /**
     * What has been typed into emoji search, or null when not searching. While this is
     * non-null the letter keys write here instead of into the target field — an IME cannot
     * put a real text field on screen without stealing focus from the field being typed into.
     */
    var emojiQuery by mutableStateOf<String?>(null)

    /** Matches for [emojiQuery], computed by the service so the UI stays a pure render. */
    var emojiResults by mutableStateOf<List<String>>(emptyList())

    /** Emoji picked most recently, newest first — the emoji page's first tab. */
    var recentEmoji by mutableStateOf<List<String>>(emptyList())

    /** Clipboard history, newest first. */
    var clips by mutableStateOf<List<ClipItem>>(emptyList())

    /**
     * The just-copied clip offered as a chip in the strip, or null. Cleared once the user
     * starts typing or enough time passes — it is an offer, not a permanent extra row.
     */
    var clipSuggestion by mutableStateOf<ClipItem?>(null)

    /** Dictation progress or its last failure, shown in place of the strip. Null when idle. */
    var voice by mutableStateOf<VoiceState?>(null)

    /**
     * A short-lived strip notice (e.g. image paste refused), shown in place of the
     * strip. An IME has no scaffold for a snackbar and a Toast is banned repo-wide,
     * so the strip is the channel; the service clears this after a few seconds.
     */
    var notice by mutableStateOf<String?>(null)
}

/**
 * Callbacks the keyboard UI invokes; implemented by the service, which owns the
 * [android.view.inputmethod.InputConnection] and applies the edits.
 */
interface ImeActions {
    fun onChar(text: String)
    fun onBackspace()
    fun onEnter()
    fun onSpace()
    fun onShift()
    fun setPage(page: KeyboardPage)
    fun commitSuggestion(word: String)

    /** Emoji search: the letter keys feed [KeyboardState.emojiQuery] until it ends. */
    fun startEmojiSearch()
    fun endEmojiSearch()

    /** Commit an emoji, leaving search mode if it was picked from the results. */
    fun commitEmoji(emoji: String)

    fun pasteClip(item: ClipItem)
    fun deleteClip(item: ClipItem)
    fun clearClips()

    /** Dismiss the strip chip without deleting the clip behind it. */
    fun dismissClipSuggestion()

    /** Start dictating, or stop and commit if it is already listening (long-press on enter). */
    fun onVoiceInput()

    /** Stop capturing and commit whatever has been heard so far. */
    fun stopVoiceInput()

    /** Clear the dictation strip, abandoning any session behind it. */
    fun dismissVoice()

    /** Open this app's system settings, the only way back from a blocked microphone. */
    fun openVoiceSettings()

    /** Shared constants and helpers for ImeActions. */
    companion object {
        /**
         * Does nothing. Lets `src/screenshotTest` render [
         * com.vayunmathur.keyboard.ui.KeyboardScreen] without an IME service behind it,
         * which is where the store listing images come from.
         */
        val Noop: ImeActions = object : ImeActions {
            override fun onChar(text: String) {}
            override fun onBackspace() {}
            override fun onEnter() {}
            override fun onSpace() {}
            override fun onShift() {}
            override fun setPage(page: KeyboardPage) {}
            override fun commitSuggestion(word: String) {}
            override fun startEmojiSearch() {}
            override fun endEmojiSearch() {}
            override fun commitEmoji(emoji: String) {}
            override fun pasteClip(item: ClipItem) {}
            override fun deleteClip(item: ClipItem) {}
            override fun clearClips() {}
            override fun dismissClipSuggestion() {}
            override fun onVoiceInput() {}
            override fun stopVoiceInput() {}
            override fun dismissVoice() {}
            override fun openVoiceSettings() {}
        }
    }
}
