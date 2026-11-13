package com.vayunmathur.games.unblockjam.platform

import com.vayunmathur.games.unblockjam.data.LevelData

/**
 * The UI contract between [UnblockJamViewModel] and the game screen.
 *
 * The screen takes a state value plus an actions interface rather than the ViewModel itself,
 * so it can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` alongside the ViewModel so the dependency runs one way:
 * the screen depends on this contract, and the ViewModel implements it.
 */

/**
 * Everything the game screen draws. [levelData] is the board as it currently stands, which
 * is the loaded level's starting layout until the player moves something.
 */
data class GameUiState(
    val levelData: LevelData,
    val levelIndex: Int = 0,
    val maxLevelIndex: Int = 0,
    val moves: Int = 0,
    val bestScore: Int? = null,
    val isCompleted: Boolean = false,
    val isLevelWon: Boolean = false,
    val canUndo: Boolean = false,
)

/** The pinned daily-challenge card above the pack list. Null while the day's pack is generating. */
data class DailyProgress(
    /** Local epoch day the pack was generated for. */
    val day: Long,
    val completed: Int,
    val total: Int,
    val streak: Long,
)

/**
 * Game screen callbacks. Every method has a no-op default so a preview can render a board
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface GameActions {
    fun onBlockMoved(newLevelData: LevelData) {}
    fun onLevelWon() {}
    fun onUndo() {}
    fun onRestart() {}

    /**
     * Jump to another puzzle in the same pack. Navigation, not game logic, so the ViewModel
     * leaves this at its default and the binder supplies it.
     */
    fun onLevelChange(newIndex: Int) {}

    /**
     * Leave the solved last puzzle of a pack for the pack list. Navigation too, so likewise left
     * to the binder.
     */
    fun onBackToPacks() {}

    companion object {
        val Noop: GameActions = object : GameActions {}
    }
}

/** What the settings screen draws. */
data class SettingsUiState(
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
)

/** Settings callbacks. Same no-op-default arrangement as [GameActions]. */
interface SettingsActions {
    fun setReminderEnabled(enabled: Boolean) {}
    fun setReminderTime(hour: Int, minute: Int) {}

    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}
