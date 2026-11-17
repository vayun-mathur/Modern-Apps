package com.vayunmathur.keyboard.platform.ime

/**
 * What one keystroke did to a composition.
 *
 * [commit] is text that has become final and goes in ahead of the (new) composing region —
 * a Hangul syllable that the next jamo pushed out, or the Han character a candidate pick
 * settled on. [composing] is what stays underlined in the field. Both may be empty: that is
 * how an engine says "the composition is now gone" (after the last backspace).
 */
data class ComposeResult(val commit: String = "", val composing: String = "")

/**
 * A composition engine: an input method for scripts where a keystroke is not a character.
 *
 * Hangul assembles jamo into syllables, pinyin trades a spelling for a Han character, romaji
 * becomes kana — all of them need keystrokes to stay editable for a while before they mean
 * anything, which is exactly what the platform's *composing region* is for. The service owns
 * the [android.view.inputmethod.InputConnection] and applies whatever these return; engines
 * hold only their own buffer, so they are plain classes and are unit tested as such.
 *
 * Every method that returns `null` means "not mine" — the service then finalizes whatever is
 * pending and handles the key the ordinary way.
 */
interface Composer {
    /** The text currently underlined in the field. */
    val composing: String

    /** Candidates to offer in the strip. Empty for engines that compose without choices. */
    val candidates: List<String> get() = emptyList()

    /** True while there is something unfinished. */
    val isComposing: Boolean get() = composing.isNotEmpty()

    /** Feed one key's text. Returns null when this engine has no use for that character. */
    fun accept(text: String): ComposeResult?

    /** Returns null when nothing is composing, so the field's own delete applies. */
    fun backspace(): ComposeResult?

    /**
     * The space bar. Engines with candidates select the best one (which is what space does
     * in every CJK IME); the rest return null and get an ordinary space.
     */
    fun space(): ComposeResult? = null

    /** Choose an offered candidate. */
    fun pick(candidate: String): ComposeResult

    /**
     * Last chance to rewrite the composing region before it is made final — romaji needs it
     * to turn a trailing `n` into `ん`. Returning null means "what is on screen is correct".
     */
    fun finish(): ComposeResult? = null

    /**
     * Abandon internal state without touching the field. The service has already finished
     * the composing region (whatever is on screen stays as typed), so this only clears the
     * engine's own buffer — on a field change, page change, or IME switch.
     */
    fun reset()
}
