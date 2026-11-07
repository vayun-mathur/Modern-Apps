package com.vayunmathur.keyboard.ime

import com.vayunmathur.keyboard.util.KeyboardSettings
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Service boot wiring split from KeyboardService (LargeClass/TooManyFunctions).
 * Behaviour identical.
 */

/** Keep [KeyboardState.settings] in sync with DataStore so changes apply live. */
internal fun KeyboardService.observeSettings() {
    val keys = KeyboardSettings.Keys
    scope.launch { ds.booleanFlow(keys.HAPTIC).collectLatest { update { copy(haptic = it) } } }
    scope.launch { ds.booleanFlow(keys.SOUND).collectLatest { update { copy(sound = it) } } }
    scope.launch { ds.booleanFlow(keys.AUTO_CAP).collectLatest { update { copy(autoCapitalize = it) } } }
    scope.launch {
        ds.booleanFlow(keys.DOUBLE_SPACE_PERIOD).collectLatest { update { copy(doubleSpacePeriod = it) } }
    }
    scope.launch { ds.booleanFlow(keys.SHOW_SUGGESTIONS).collectLatest { update { copy(showSuggestions = it) } } }
    scope.launch { ds.booleanFlow(keys.AUTO_CORRECT).collectLatest { update { copy(autoCorrect = it) } } }
    scope.launch { ds.booleanFlow(keys.NUMBER_ROW).collectLatest { update { copy(numberRow = it) } } }
    scope.launch {
        ds.booleanFlow(keys.CLIPBOARD).collectLatest {
            update { copy(clipboardEnabled = it) }
            if (!it) forgetClips()
        }
    }
    // Settings wipes the history by bumping the wipe counter; a counter (not a blank
    // string) means the one-time legacy migration below can clear the old key without
    // looking like a wipe request. What has actually been carried out is persisted in
    // CLIPS_WIPE_APPLIED, so a clear tapped while this service was not running is
    // still honoured on the next start instead of being read as the baseline, while
    // an already-applied wipe (the marker's first write) never fires twice.
    scope.launch {
        ds.longFlow(keys.CLIPS_WIPE, 0L).collectLatest { wipe ->
            val applied = ds.getLong(keys.CLIPS_WIPE_APPLIED)
            if (applied == null) {
                ds.setLong(keys.CLIPS_WIPE_APPLIED, wipe)
            } else if (wipe > applied) {
                forgetClips()
                ds.setLong(keys.CLIPS_WIPE_APPLIED, wipe)
            }
        }
    }
    scope.launch { ds.doubleFlow(keys.KEY_HEIGHT).collectLatest { update { copy(keyHeightScale = it.toFloat()) } } }
    scope.launch {
        ds.stringFlow(keys.ACTIVE_LAYOUT).collectLatest {
            update { copy(activeLayoutId = it) }
            syncComposer()
        }
    }
}

/**
 * Read the navigation-bar height from the window so the keyboard can pad clear of
 * it. Reads immediately and again after the next layout pass (rootWindowInsets is
 * often not populated yet when onWindowShown/onStartInputView first run).
 */
internal fun KeyboardService.updateBottomInset() {
    val decor = window?.window?.decorView ?: return
    val read = {
        decor.rootWindowInsets?.let {
            kbState.bottomInsetPx = it.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        }
    }
    read()
    decor.post { read() }
}
