package com.vayunmathur.games.pipes.platform

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.LevelData

/**
 * The UI contract between [PipesViewModel] and the screens.
 *
 * Screens take a state value plus an actions interface rather than the ViewModel itself,
 * so they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` rather than next to the screens so the dependency
 * runs one way: the screens depend on `util`, and the ViewModel implements [PipesActions].
 */

/** One row of the pack selector. Flattened to plain counts so the screen needs neither the
 * asset-loaded packs nor the stats repository.
 */
data class PackProgress(
    val name: String,
    val shape: String,
    val completed: Int,
    val total: Int,
)

/** The pinned daily-challenge card above the pack list. Null while the day's pack is generating. */
data class DailyProgress(
    /** Local epoch day the pack was generated for. */
    val day: Long,
    val completed: Int,
    val total: Int,
    val streak: Long,
)

/** Everything the game screen draws for one level. */
data class GameBoardUiState(
    val levelData: LevelData,
    val levelIndex: Int,
    val maxLevelIndex: Int,
    val gameState: PipesGameState = PipesGameState(),
    val activeColor: Int? = null,
    val activePath: List<CellPos> = emptyList(),
    val isLevelWon: Boolean = false,
    /** Whether this level has been solved before, i.e. it has a recorded best score. */
    val isCompleted: Boolean = false,
    val colorblind: Boolean = false,
    val moves: Int = 0,
    val bestScore: Int? = null,
    val canUndo: Boolean = false,
    /** Every pair is connected but cells are still empty: show the "fill every cell" hint. */
    val showFillHint: Boolean = false,
)

/**
 * Board callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface PipesActions {
    fun startDraw(cell: CellPos) {}
    fun extendPath(cell: CellPos) {}
    fun commitDraw() {}
    fun onUndo() {}
    fun onRestart() {}
    companion object {
        val Noop: PipesActions = object : PipesActions {}
    }
}

/** What the settings screen draws. */
data class SettingsUiState(
    val colorblind: Boolean = false,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
)

/** Settings callbacks. Same no-op-default arrangement as [PipesActions]. */
interface SettingsActions {
    fun setColorblind(enabled: Boolean) {}
    fun setReminderEnabled(enabled: Boolean) {}
    fun setReminderTime(hour: Int, minute: Int) {}

    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}
