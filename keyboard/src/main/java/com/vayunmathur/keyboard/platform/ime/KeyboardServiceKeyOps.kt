package com.vayunmathur.keyboard.platform.ime

import android.os.SystemClock
import android.view.KeyEvent
import com.vayunmathur.keyboard.domain.ShiftState

/**
 * Letter-key actions split from KeyboardService (LargeClass/TooManyFunctions).
 * Behaviour identical.
 */

/** Max gap between two shift taps to latch caps-lock. */
private const val DOUBLE_TAP_MS = 300L

internal fun KeyboardService.onChar(text: String) {
    feedback()
    // The chip is an offer made before typing starts; the first keystroke declines it.
    dismissClipSuggestion()
    if (typeIntoSearch(text)) return
    val ic = currentInputConnection ?: return
    val engine = composer
    if (engine != null) {
        val result = engine.accept(text)
        if (result != null) {
            applyComposition(ic, engine, result)
            consumeShift()
            return
        }
        // Punctuation, a symbol-page key: settle the composition and type it plainly.
        finishComposition(ic)
        commit(ic, text)
        consumeShift()
        return
    }
    if (useComposing() && text.length == 1 && text[0].isLetter()) {
        composing.append(text)
        setComposing(ic, composing)
        updateSuggestions()
    } else {
        commitCurrentWord(ic, autoCorrect = false)
        commit(ic, text)
        kbState.suggestions = emptyList()
    }
    consumeShift()
    updateAutoCapShift()
}

internal fun KeyboardService.onBackspace() {
    feedback()
    val query = kbState.emojiQuery
    if (query != null) {
        // Backspacing an empty query leaves search rather than deleting from the field
        // the user cannot see.
        if (query.isEmpty()) endEmojiSearch() else setQuery(query.dropLast(1))
        return
    }
    val ic = currentInputConnection ?: return
    val engine = composer
    if (engine != null) {
        // Backspace takes a composition apart one jamo/letter at a time, and only deletes
        // from the field once there is no composition left.
        val result = engine.backspace()
        if (result != null) {
            applyComposition(ic, engine, result)
            return
        }
    }
    if (composing.isNotEmpty()) {
        composing.deleteCharAt(composing.length - 1)
        if (composing.isEmpty()) {
            setComposing(ic, "")
            finishComposing(ic)
            kbState.suggestions = emptyList()
        } else {
            setComposing(ic, composing)
            updateSuggestions()
        }
    } else if (before.hasSelection) {
        // Deleting a selection leaves whatever preceded it in front of the cursor, which
        // is text we never saw, so the mirror has to start again.
        commit(ic, "")
        before.invalidate()
    } else {
        deleteBackward(ic)
    }
    updateAutoCapShift()
}

internal fun KeyboardService.onEnter() {
    feedback()
    if (kbState.emojiQuery != null) {
        endEmojiSearch()
        return
    }
    val ic = currentInputConnection ?: return
    // Finish the composing word first: performEditorAction hands control to the app,
    // which would otherwise read the field without the last (still-composing) word.
    finishComposition(ic)
    commitCurrentWord(ic, autoCorrect = false)
    // performEditorAction returns false when the target can't handle the action (a
    // dead connection, or an actionId the app declines). Fall back to a real Enter so
    // the key never silently does nothing.
    val handled = enterSendsAction && ic.performEditorAction(editorActionId)
    if (!handled) {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }
    // Either way the field is now the app's business — it may have inserted a newline,
    // moved focus or submitted and cleared itself.
    before.invalidate()
    kbState.suggestions = emptyList()
    updateAutoCapShift()
}

internal fun KeyboardService.onSpace() {
    feedback()
    // Multi-word emoji names are common ("cat face"), so space is part of the query.
    if (typeIntoSearch(" ")) return
    val ic = currentInputConnection ?: return
    val engine = composer
    if (engine != null) {
        // In a CJK engine space means "take the best candidate", not "type a space".
        val result = engine.space()
        if (result != null) {
            applyComposition(ic, engine, result)
            return
        }
        finishComposition(ic)
        commit(ic, " ")
        lastSpaceTime = SystemClock.uptimeMillis()
        return
    }
    commitCurrentWord(ic, autoCorrect = kbState.settings.autoCorrect)
    val text = textBeforeCursor()
    val now = SystemClock.uptimeMillis()
    val doubleSpace = kbState.settings.doubleSpacePeriod && text.length >= 2 &&
        text[text.length - 1] == ' ' && text[text.length - 2].isLetterOrDigit() &&
        now - lastSpaceTime < 1000
    if (doubleSpace) {
        deleteBackward(ic)
        commit(ic, ". ")
    } else {
        commit(ic, " ")
    }
    lastSpaceTime = now
    kbState.suggestions = emptyList()
    updateAutoCapShift()
}

internal fun KeyboardService.onShift() {
    feedback()
    // Apply shift immediately on every tap; a quick second tap (while already shifted)
    // latches caps-lock. No waiting for a double-tap, so shift feels instant.
    // A rapid tap while latched unlatches: without it the third tap of a
    // triple-tap re-latched and caps-lock had no quick exit.
    val now = SystemClock.uptimeMillis()
    val rapid = now - lastShiftTime < DOUBLE_TAP_MS
    kbState.shift = when {
        kbState.shift == ShiftState.CAPS_LOCK && rapid -> ShiftState.OFF
        rapid && kbState.shift != ShiftState.OFF -> ShiftState.CAPS_LOCK
        kbState.shift == ShiftState.OFF -> ShiftState.SHIFTED
        else -> ShiftState.OFF
    }
    lastShiftTime = now
}

internal fun KeyboardService.commitSuggestion(word: String) {
    feedback()
    val ic = currentInputConnection ?: return
    val engine = composer
    if (engine != null) {
        // A picked candidate is the character itself; no trailing space, unlike a word.
        applyComposition(ic, engine, engine.pick(word))
        return
    }
    setComposing(ic, word)
    finishComposing(ic)
    commit(ic, " ")
    composing.setLength(0)
    lastSpaceTime = SystemClock.uptimeMillis()
    kbState.suggestions = emptyList()
    updateAutoCapShift()
}
