package com.vayunmathur.keyboard.util

import com.vayunmathur.library.util.DataStoreUtils

/**
 * All user-tunable keyboard settings, snapshot into one immutable value so both the
 * setup Activity and the IME service read the same shape. Persisted via [DataStoreUtils]
 * (shared, offline preferences) under the [Keys] names.
 */
data class KeyboardSettings(
    val haptic: Boolean = true,
    val sound: Boolean = false,
    val autoCapitalize: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val showSuggestions: Boolean = true,
    val autoCorrect: Boolean = false,
    /** Show a persistent 1–0 number row above the letters. */
    val numberRow: Boolean = true,
    /** Record what the user copies and offer it back. Off means capture nothing at all. */
    val clipboardEnabled: Boolean = true,
    /** Multiplier on the base key height (0.8..1.4). */
    val keyHeightScale: Float = 1f,
    /** Id of the layout currently in use, resolved against the catalog. */
    val activeLayoutId: String = KeyboardLayouts.DEFAULT.id,
) {
    /** The layout the letter page draws, from the catalog, falling back to the default. */
    val activeLayout: KeyboardLayout
        get() = KeyboardLayouts.byId(activeLayoutId) ?: KeyboardLayouts.DEFAULT

    object Keys {
        const val HAPTIC = "kb_haptic"
        const val SOUND = "kb_sound"
        const val AUTO_CAP = "kb_auto_cap"
        const val DOUBLE_SPACE_PERIOD = "kb_double_space_period"
        const val SHOW_SUGGESTIONS = "kb_show_suggestions"
        const val AUTO_CORRECT = "kb_auto_correct"
        const val NUMBER_ROW = "kb_number_row"
        const val CLIPBOARD = "kb_clipboard"
        const val CLIPS = "kb_clips"
        /**
         * Bumped whenever the history must be forgotten (settings "clear"). A counter, not
         * a blanked string: blanking the stored clips as the wipe signal meant the
         * startup migration clearing the legacy key looked like a wipe request.
         */
        const val CLIPS_WIPE = "kb_clips_wipe"
        /**
         * The wipe-counter value the IME has actually carried out. A clear requested
         * while the service is not running has to survive until its next start, which
         * a "first flow emission is the baseline" rule cannot distinguish from an
         * already-applied wipe; comparing against this marker can.
         */
        const val CLIPS_WIPE_APPLIED = "kb_clips_wipe_applied"
        const val EMOJI_RECENTS = "kb_emoji_recents"
        const val KEY_HEIGHT = "kb_key_height"
        const val ACTIVE_LAYOUT = "kb_active_layout"
    }

    companion object {
        /** Read the current persisted snapshot (defaults applied for unset keys). */
        fun load(ds: DataStoreUtils): KeyboardSettings = KeyboardSettings(
            haptic = ds.getBoolean(Keys.HAPTIC, true),
            sound = ds.getBoolean(Keys.SOUND, false),
            autoCapitalize = ds.getBoolean(Keys.AUTO_CAP, true),
            doubleSpacePeriod = ds.getBoolean(Keys.DOUBLE_SPACE_PERIOD, true),
            showSuggestions = ds.getBoolean(Keys.SHOW_SUGGESTIONS, true),
            autoCorrect = ds.getBoolean(Keys.AUTO_CORRECT, false),
            numberRow = ds.getBoolean(Keys.NUMBER_ROW, true),
            clipboardEnabled = ds.getBoolean(Keys.CLIPBOARD, true),
            keyHeightScale = (ds.getDouble(Keys.KEY_HEIGHT) ?: 1.0).toFloat(),
            activeLayoutId = ds.getString(Keys.ACTIVE_LAYOUT) ?: KeyboardLayouts.DEFAULT.id,
        )
    }
}
