package com.vayunmathur.keyboard.platform.ime

import android.media.AudioManager
import android.view.inputmethod.InputConnection
import com.vayunmathur.keyboard.domain.KeyboardPage
import com.vayunmathur.keyboard.data.KeyboardSettings
import com.vayunmathur.keyboard.domain.ShiftState

/**
 * Field-edit and suggestion helpers split from KeyboardService
 * (LargeClass/TooManyFunctions). Behaviour identical.
 */

/** Suggestions shown in the strip for the current composing prefix. */
internal const val MAX_SUGGESTIONS = 3

/**
 * The four ways this service changes the field, each mirrored into
 * [KeyboardService.before]. Going through these rather than touching the
 * [InputConnection] directly is what lets auto-capitalisation and the
 * double-space period answer from memory instead of asking the target app —
 * see [TextBeforeCursor].
 */
internal fun KeyboardService.commit(ic: InputConnection, text: CharSequence) {
    ic.commitText(text, 1)
    pendingComposition = ""
    before.committed(text)
}

internal fun KeyboardService.setComposing(ic: InputConnection, text: CharSequence) {
    ic.setComposingText(text, 1)
    pendingComposition = text.toString()
    before.composing(text)
}

internal fun KeyboardService.finishComposing(ic: InputConnection) {
    ic.finishComposingText()
    before.composingFinished(pendingComposition)
    pendingComposition = ""
}

internal fun KeyboardService.deleteBackward(ic: InputConnection) {
    // Code points, not Java chars: a single emoji is two UTF-16 units, and
    // deleting one unit leaves a lone surrogate (�) in the field.
    ic.deleteSurroundingTextInCodePoints(1, 0)
    before.deleted()
}

/** The text in front of the cursor, asking the field only when the mirror cannot say. */
internal fun KeyboardService.textBeforeCursor(): CharSequence {
    before.peek()?.let { return it }
    val ic = currentInputConnection ?: return ""
    val text = ic.getTextBeforeCursor(TextBeforeCursor.WINDOW, 0) ?: return ""
    before.fill(text)
    return text
}

internal inline fun KeyboardService.update(transform: KeyboardSettings.() -> KeyboardSettings) {
    kbState.settings = kbState.settings.transform()
}

/** Finish the composing word, optionally replacing it with an autocorrect suggestion. */
internal fun KeyboardService.commitCurrentWord(ic: InputConnection, autoCorrect: Boolean) {
    if (composing.isEmpty()) return
    if (autoCorrect) {
        // autocorrect already declines to rewrite a word it knows, so there is no need
        // to look the typed word up separately first.
        val typed = composing.toString()
        val fix = dictionary.autocorrect(typed)
        if (fix != null && !fix.equals(typed, ignoreCase = true)) {
            setComposing(ic, fix)
        }
    }
    finishComposing(ic)
    composing.setLength(0)
}

internal fun KeyboardService.updateSuggestions() {
    if (!kbState.settings.showSuggestions || !useComposing()) {
        kbState.suggestions = emptyList()
        return
    }
    val prefix = composing.toString()
    kbState.suggestions = if (prefix.isBlank()) {
        emptyList()
    } else {
        dictionary.suggestions(prefix, MAX_SUGGESTIONS)
    }
}

internal fun KeyboardService.consumeShift() {
    if (kbState.shift == ShiftState.SHIFTED) kbState.shift = ShiftState.OFF
}

/** Auto-capitalize the shift key when the cursor sits at the start of a sentence. */
internal fun KeyboardService.updateAutoCapShift() {
    if (kbState.basePage != KeyboardPage.LETTERS) return
    // Shift is a second character layer, not upper case, in scripts like Devanagari or
    // Thai — auto-capitalizing there would silently swap the whole layout.
    if (!kbState.effectiveLayout.cased) return
    if (kbState.passwordField) return
    if (kbState.shift == ShiftState.CAPS_LOCK) return
    if (!kbState.settings.autoCapitalize) return
    if (composing.isNotEmpty()) return
    // Email/URL fields never capitalize; name fields capitalize every word, everything
    // else capitalizes at sentence starts.
    val atStart = when (kbState.textVariation) {
        TextVariation.EMAIL, TextVariation.URL -> false
        TextVariation.PERSON_NAME -> isAtWordStart()
        else -> isAtSentenceStart()
    }
    kbState.shift = if (atStart) ShiftState.SHIFTED else ShiftState.OFF
}

internal fun KeyboardService.isAtSentenceStart(): Boolean {
    val text = textBeforeCursor()
    if (text.isEmpty()) return true
    val last = text[text.length - 1]
    if (last == '\n') return true
    if (text.length < 2) return false
    val prev = text[text.length - 2]
    return last == ' ' && (prev == '.' || prev == '?' || prev == '!')
}

/** True at the start of any word: empty field, after a newline, or after a space. */
internal fun KeyboardService.isAtWordStart(): Boolean {
    val text = textBeforeCursor()
    if (text.isEmpty()) return true
    val last = text[text.length - 1]
    return last == '\n' || last == ' '
}

/**
 * Keypress sound only. Haptics moved to the press site in the key composables
 * ([rememberKeyHapticTick]): a service has no CompositionLocal, and ticking where the
 * press commits keeps keys, strips and pages from ever double-ticking one tap.
 */
internal fun KeyboardService.feedback() {
    if (kbState.settings.sound) {
        audio?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }
}
