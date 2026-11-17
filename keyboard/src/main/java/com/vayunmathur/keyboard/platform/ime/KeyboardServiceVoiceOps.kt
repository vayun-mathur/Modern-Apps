package com.vayunmathur.keyboard.platform.ime

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import com.vayunmathur.keyboard.platform.VoiceFailure
import com.vayunmathur.keyboard.platform.VoicePermission
import com.vayunmathur.keyboard.platform.VoicePermissionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dictation split from KeyboardService (LargeClass/TooManyFunctions).
 * Behaviour identical.
 */

/** How long a dictation failure stays in the strip before it clears itself. */
private const val VOICE_MESSAGE_MS = 6_000L

/** How long a granted microphone still counts as "the user just asked for dictation". */
internal const val VOICE_GRANT_MS = 30_000L

/**
 * Long-press on enter. Pressing again while the microphone is live stops it, so the one
 * gesture both starts and ends a dictation.
 */
internal fun KeyboardService.onVoiceInput() {
    feedback()
    if (kbState.voice is VoiceState.Listening) {
        stopVoiceInput()
        return
    }
    // Checked up front rather than left to the recognizer: on a device with no
    // recognition service there is nothing to bind to, and saying so is more use than
    // a generic failure.
    if (!voiceInput.isAvailable()) {
        showVoiceFailure(VoiceFailure.UNAVAILABLE)
        return
    }
    if (!VoicePermission.isGranted(this)) {
        setVoiceState(null)
        VoicePermission.request(this)
        return
    }
    beginListening()
}

internal fun KeyboardService.stopVoiceInput() {
    // stopListening, not cancel: the recognizer still reports the words it already has.
    voiceInput.stop()
    if (kbState.voice is VoiceState.Listening) setVoiceState(VoiceState.Transcribing)
}

internal fun KeyboardService.dismissVoice() {
    voiceInput.cancel()
    setVoiceState(null)
}

internal fun KeyboardService.openVoiceSettings() {
    dismissVoice()
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

internal fun KeyboardService.onVoicePermissionResult(result: VoicePermissionResult) {
    when (result) {
        // The prompt took the focus, which hid the keyboard along with the field. Wait
        // for both back before listening, or the transcript would have nowhere to go.
        VoicePermissionResult.GRANTED -> {
            if (windowShown) beginListening() else voiceGrantedAt = SystemClock.uptimeMillis()
        }
        VoicePermissionResult.DENIED -> showVoiceFailure(VoiceFailure.PERMISSION_DENIED)
        VoicePermissionResult.BLOCKED -> showVoiceFailure(VoiceFailure.PERMISSION_BLOCKED)
    }
}

internal fun KeyboardService.beginListening() {
    setVoiceState(VoiceState.Listening(""))
    voiceInput.start(
        // Dictate in the language the user chose to type in, which is a better guess
        // than the device locale on a keyboard whose whole point is switching scripts.
        languageTag = kbState.settings.activeLayout.id.substringBefore('_'),
        onPartial = { partial ->
            if (kbState.voice is VoiceState.Listening) {
                setVoiceState(VoiceState.Listening(partial))
            }
        },
        onTranscribing = {
            if (kbState.voice is VoiceState.Listening) setVoiceState(VoiceState.Transcribing)
        },
        onFinal = { text ->
            setVoiceState(null)
            commitVoiceText(text)
        },
        onFailure = { showVoiceFailure(it) },
    )
}

/**
 * Put a transcript into the field. Anything still composing is settled first — dictated
 * text is finished text, not a continuation of the half-typed word — and `commitText`
 * then replaces the selection, which is what dictating over selected text should do.
 */
internal fun KeyboardService.commitVoiceText(text: String) {
    val ic = currentInputConnection ?: return
    finishComposition(ic)
    commitCurrentWord(ic, autoCorrect = false)
    // Read before committing: the mirror drops the selection as soon as it is told.
    val replacedSelection = before.hasSelection
    val needsSpace = !replacedSelection &&
        textBeforeCursor().lastOrNull()?.isWhitespace() == false
    commit(ic, if (needsSpace) " $text" else text)
    // What surrounded the replaced selection is text we never saw.
    if (replacedSelection) before.invalidate()
    kbState.suggestions = emptyList()
    updateAutoCapShift()
}

internal fun KeyboardService.showVoiceFailure(failure: VoiceFailure) {
    setVoiceState(VoiceState.Failed(failure))
}

internal fun KeyboardService.setVoiceState(state: VoiceState?) {
    voiceMessageJob?.cancel()
    voiceMessageJob = null
    kbState.voice = state
    // A blocked microphone is the one failure that carries an action, so it waits to be
    // read and dismissed instead of timing out from under the user's finger.
    if (state !is VoiceState.Failed || state.failure == VoiceFailure.PERMISSION_BLOCKED) return
    voiceMessageJob = scope.launch {
        delay(VOICE_MESSAGE_MS)
        if (kbState.voice == state) kbState.voice = null
    }
}
