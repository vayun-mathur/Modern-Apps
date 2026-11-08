package com.vayunmathur.games.pipes.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.CompletedLevelsRepository
import com.vayunmathur.games.pipes.domain.DailyLevels
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.LevelPack
import com.vayunmathur.library.util.LevelStats
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DailyChallengeStore
import com.vayunmathur.library.util.DailyStreakReporter
import com.vayunmathur.library.work.DailyPuzzleReminder
import com.vayunmathur.library.work.DailyReminderSettings
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.LevelStatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PipesGameState(
    val paths: Map<Int, List<CellPos>> = emptyMap(),
    val cellOwner: Map<CellPos, Int> = emptyMap()
)

data class PipesUiState(
    val packIndex: Int = -1,
    val levelIndex: Int = -1,
    val levelData: LevelData? = null,
    val gameState: PipesGameState = PipesGameState(),
    val history: List<PipesGameState> = emptyList(),
    val isLevelWon: Boolean = false,
    val activeColor: Int? = null,
    val activePath: List<CellPos> = emptyList(),
    val preDrawState: PipesGameState? = null
)

class PipesViewModel(application: Application) : AndroidViewModel(application), PipesActions {

    val repository: CompletedLevelsRepository = CompletedLevelsRepository(application)

    val achievementsManager: AchievementsManager = run {
        val json = application.assets.open("achievements.json")
            .bufferedReader().use { it.readText() }
        PipesAchievementsManager(application, json, repository)
    }

    private val _uiState = MutableStateFlow(PipesUiState())
    val uiState: StateFlow<PipesUiState> = _uiState.asStateFlow()

    private val _levelStats =
        MutableStateFlow<Map<String, LevelStats>>(repository.getLevelStats())
    val levelStats: StateFlow<Map<String, LevelStats>> = _levelStats.asStateFlow()

    private val ds = DataStoreUtils.getInstance(application)

    // ---- Daily challenge ----

    /**
     * Daily scores live in their own prefs file, pruned to the current day. They must not land in
     * [repository], whose map size feeds the `level_50` achievement and the pack-completion
     * checks — five new level IDs a day would inflate both.
     */
    private val dailyRepository = LevelStatsRepository(application, "daily_stats")

    private val dailyStore = DailyChallengeStore(application, DAILY_KEY_PREFIX)

    private val _dailyDay = MutableStateFlow(dailyStore.todayEpochDay())
    val dailyDay: StateFlow<Long> = _dailyDay.asStateFlow()

    private val _dailyPack = MutableStateFlow<LevelPack?>(null)
    val dailyPack: StateFlow<LevelPack?> = _dailyPack.asStateFlow()

    private val _dailyStats = MutableStateFlow(dailyRepository.getLevelStats())
    val dailyStats: StateFlow<Map<String, LevelStats>> = _dailyStats.asStateFlow()

    val dailyStreak: StateFlow<Long> = dailyStore.currentStreak
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** How many of today's daily levels are solved. Derived from the IDs, so it needs no pack. */
    val dailyCompleted: StateFlow<Int> =
        combine(_dailyDay, _dailyStats) { day, stats ->
            (0 until DailyLevels.LEVELS_PER_DAY)
                .count { stats.containsKey(DailyLevels.levelId(day, it)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val colorblind: StateFlow<Boolean> = ds.booleanFlow(KEY_COLORBLIND)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ds.getBoolean(KEY_COLORBLIND, false))

    fun setColorblind(value: Boolean) {
        viewModelScope.launch { ds.setBoolean(KEY_COLORBLIND, value) }
    }

    private val reminderSettings = DailyReminderSettings(application, DAILY_KEY_PREFIX)

    val reminderEnabled: StateFlow<Boolean> = reminderSettings.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val reminderMinutesOfDay: StateFlow<Long> = reminderSettings.minutesOfDay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyPuzzleReminder.DEFAULT_MINUTES_OF_DAY)

    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            reminderSettings.setEnabled(enabled)
            rescheduleReminder(enabled, reminderMinutesOfDay.value)
        }
    }

    fun setReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            val minutes = (hour * 60 + minute).toLong()
            reminderSettings.setMinutesOfDay(minutes)
            rescheduleReminder(reminderEnabled.value, minutes)
        }
    }

    private fun rescheduleReminder(enabled: Boolean, minutesOfDay: Long) {
        DailyPuzzleReminder.update(
            context = getApplication(),
            keyPrefix = DAILY_KEY_PREFIX,
            notificationId = REMINDER_NOTIFICATION_ID,
            enabled = enabled,
            hour = (minutesOfDay / 60).toInt(),
            minute = (minutesOfDay % 60).toInt(),
        )
    }

    init {
        viewModelScope.launch {
            achievementsManager.checkExistingAchievements()
        }
    }

    /**
     * Picks the day's pack off the main thread — deliberately not in `LevelPack.init()`, which runs
     * synchronously in `onCreate`. Only runs when the player opens the daily; the pack-list card
     * derives its progress from the level IDs alone.
     */
    fun refreshDaily() {
        viewModelScope.launch(Dispatchers.Default) {
            val today = dailyStore.todayEpochDay()
            if (_dailyDay.value == today && _dailyPack.value != null) return@launch
            _dailyDay.value = today
            withContext(Dispatchers.IO) {
                // Only today's pack is ever playable, so yesterday's scores are dead weight.
                dailyRepository.retainOnly(todayLevelIds(today))
                _dailyStats.value = dailyRepository.getLevelStats()
            }
            _dailyPack.value = DailyLevels.packFor(today)
        }
    }

    private fun todayLevelIds(day: Long): Set<String> =
        (0 until DailyLevels.LEVELS_PER_DAY)
            .mapTo(mutableSetOf()) { DailyLevels.levelId(day, it) }

    /** Resolves a pack index to its levels, covering both shipped packs and the daily pack. */
    private fun levelsFor(packIndex: Int): List<LevelData>? = when (packIndex) {
        DAILY_PACK_INDEX -> _dailyPack.value?.levels
        in LevelPack.PACKS.indices -> LevelPack.PACKS[packIndex].levels
        else -> null
    }

    fun loadLevel(packIndex: Int, levelIndex: Int) {
        val levelData = levelsFor(packIndex)?.getOrNull(levelIndex) ?: return
        val current = _uiState.value
        // Same-index reloads are a no-op only while they resolve to the same board. The
        // daily pack is regenerated at midnight, so a DailyGame route kept open across the
        // rollover must pick up the new day's board instead of keeping yesterday's (whose
        // IDs the stats store has already pruned, silently losing the win).
        if (current.packIndex == packIndex &&
            current.levelIndex == levelIndex &&
            current.levelData == levelData
        ) return
        _uiState.value = PipesUiState(
            packIndex = packIndex,
            levelIndex = levelIndex,
            levelData = levelData,
            gameState = PipesGameState(),
        )
    }

    override fun startDraw(cell: CellPos) {
        val s = _uiState.value
        if (s.isLevelWon || s.levelData == null) return

        val levelData = s.levelData
        val endpointColor = levelData.endpoints.find { ep -> cell in ep.cells }?.colorIndex

        if (endpointColor != null) {
            // Grabbing an endpoint always restarts that color: its current line breaks
            // right away (like grabbing a line partway does) so the drag is free to take
            // a different route. Releasing without drawing leaves the line cleared.
            val brokenState = clearColor(s.gameState, endpointColor)
            _uiState.update {
                it.copy(
                    activeColor = endpointColor,
                    activePath = listOf(cell),
                    gameState = brokenState,
                    preDrawState = it.gameState
                )
            }
            return
        }

        val ownerColor = s.gameState.cellOwner[cell] ?: return
        val path = s.gameState.paths[ownerColor] ?: return
        val idx = path.indexOf(cell)
        if (idx >= 0) {
            _uiState.update {
                it.copy(
                    activeColor = ownerColor,
                    activePath = path.take(idx + 1),
                    preDrawState = it.gameState,
                )
            }
        }
    }

    /** Removes [color]'s line from the board, leaving every other color untouched. */
    private fun clearColor(state: PipesGameState, color: Int): PipesGameState {
        val remaining = state.paths - color
        // Rebuild ownership so a bridge shared with the cleared color falls back to its
        // remaining crosser instead of being orphaned (single-owner map caveat).
        val newCellOwner = mutableMapOf<CellPos, Int>()
        for ((c, path) in remaining) {
            for (cell in path) {
                val current = newCellOwner[cell]
                if (current == null || current == c) {
                    newCellOwner[cell] = c
                }
            }
        }
        // Non-bridge cells keep first-writer wins implicitly via path order; bridges allow sharing
        // so last-writer fallback above is fine — commitDraw preserves first owner on redraw.
        return PipesGameState(remaining, newCellOwner)
    }

    override fun extendPath(cell: CellPos) {
        val s = _uiState.value
        val activeColor = s.activeColor ?: return
        val levelData = s.levelData ?: return
        if (s.isLevelWon || cell !in levelData.cells) return
        val currentPath = s.activePath
        if (currentPath.isEmpty() || isAtPairedEndpoint(levelData, activeColor, currentPath)) return
        if (tryBacktrack(currentPath, cell)) return
        if (isValidStep(s, levelData, activeColor, currentPath, cell)) {
            _uiState.update { it.copy(activePath = currentPath + cell) }
        }
    }

    private fun isAtPairedEndpoint(
        levelData: LevelData,
        activeColor: Int,
        path: List<CellPos>,
    ): Boolean {
        val endpointCells = levelData.endpoints.find { it.colorIndex == activeColor }?.cells
            ?: return false
        if (path.first() !in endpointCells || path.last() !in endpointCells) return false
        if (path.first() == path.last()) return false
        // Pairs: the other end. Triplets (chain): all dots visited, ending on a dot.
        return if (endpointCells.size == 2) {
            val paired = if (path.first() == endpointCells[0]) endpointCells[1] else endpointCells[0]
            path.last() == paired
        } else {
            endpointCells.all { it in path }
        }
    }

    private fun tryBacktrack(currentPath: List<CellPos>, cell: CellPos): Boolean {
        if (currentPath.size < BACKTRACK_MIN_LENGTH || cell != currentPath[currentPath.size - 2]) {
            return false
        }
        _uiState.update { it.copy(activePath = currentPath.dropLast(1)) }
        return true
    }

    private fun isValidStep(
        s: PipesUiState,
        levelData: LevelData,
        activeColor: Int,
        path: List<CellPos>,
        cell: CellPos,
    ): Boolean {
        if (cell in path) return false
        val neighbors = levelData.adjacency[path.last()] ?: return false
        if (cell !in neighbors || isForeignEndpoint(levelData, activeColor, cell)) return false
        if (!isStraightThroughBridge(levelData, path, cell)) return false
        return isFreeForColor(s, levelData, activeColor, cell)
    }

    private fun isForeignEndpoint(levelData: LevelData, activeColor: Int, cell: CellPos): Boolean =
        levelData.endpoints.filter { it.colorIndex != activeColor }
            .flatMap { it.cells }
            .contains(cell)

    /**
     * Bridges read as an overpass only when both pipes run straight through. When the path
     * leaves a bridge cell, the exit must continue the entry line, so a pipe cannot corner
     * on the crossing. Long-range exits (portal/wrap jumps) are not grid lines and skip
     * the check.
     */
    private fun isStraightThroughBridge(
        levelData: LevelData,
        path: List<CellPos>,
        cell: CellPos,
    ): Boolean {
        val origin = path.lastOrNull() ?: return true
        if (origin !in levelData.bridges || path.size < 2) return true
        if (kotlin.math.abs(cell.row - origin.row) + kotlin.math.abs(cell.col - origin.col) != 1) {
            return true
        }
        val entry = path[path.size - 2]
        return (entry.row == origin.row && origin.row == cell.row) ||
            (entry.col == origin.col && origin.col == cell.col)
    }

    private fun isFreeForColor(
        s: PipesUiState,
        levelData: LevelData,
        activeColor: Int,
        cell: CellPos,
    ): Boolean {
        val owner = s.gameState.cellOwner[cell] ?: return true
        return owner == activeColor || cell in levelData.bridges
    }

    override fun commitDraw() {
        val s = _uiState.value
        val activeColor = s.activeColor ?: return
        if (s.isLevelWon) return

        val newPath = s.activePath
        val preDrawState = s.preDrawState ?: s.gameState

        if (newPath.size < 2) {
            if (s.gameState != preDrawState) {
                // Tapped an endpoint without drawing: keep the line broken, but make it undoable.
                _uiState.update {
                    it.copy(
                        history = it.history + preDrawState,
                        activeColor = null,
                        activePath = emptyList(),
                        preDrawState = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        activeColor = null,
                        activePath = emptyList(),
                        gameState = preDrawState,
                        preDrawState = null,
                    )
                }
            }
            return
        }

        val currentState = s.gameState
        val newCellOwner = currentState.cellOwner.toMutableMap()

        currentState.paths[activeColor]?.forEach { c ->
            if (newCellOwner[c] == activeColor) newCellOwner.remove(c)
        }

        val newPaths = currentState.paths.toMutableMap()
        newPaths[activeColor] = newPath
        val bridges = s.levelData?.bridges ?: emptySet()
        for (cell in newPath) {
            if (cell in bridges && newCellOwner[cell] != null && newCellOwner[cell] != activeColor) continue
            newCellOwner[cell] = activeColor
        }

        val newGameState = PipesGameState(newPaths, newCellOwner)

        _uiState.update {
            it.copy(
                gameState = newGameState,
                history = it.history + preDrawState,
                activeColor = null,
                activePath = emptyList(),
                preDrawState = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.incrementTotalPipesPlaced()
            achievementsManager.onProgressUpdated("pipes_1000", repository.getTotalPipesPlaced())
        }

        checkWin()
    }

    private fun checkWin() {
        val s = _uiState.value
        val levelData = s.levelData ?: return
        val gameState = s.gameState

        if (gameState.cellOwner.size != levelData.cells.size) return

        val allConnected = levelData.endpoints.all { ep ->
            val path = gameState.paths[ep.colorIndex] ?: return
            path.size >= 2 && path.first() in ep.cells &&
                (if (ep.cells.size == 2) path.last() in ep.cells - path.first()
                else ep.cells.all { it in path } && path.last() in ep.cells)
        }

        if (allConnected) onLevelWon()
    }

    private fun onLevelWon() {
        val s = _uiState.value
        if (s.isLevelWon || s.packIndex == NO_PACK_INDEX) return
        _uiState.update { it.copy(isLevelWon = true) }

        val level = s.levelData ?: return
        val moves = getCurrentMoves()

        if (s.packIndex == DAILY_PACK_INDEX) {
            onDailyLevelWon(level.id, moves, level.optimalMoves)
            return
        }

        viewModelScope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                repository.updateBestScore(level.id, moves)
                repository.getLevelStats()
            }
            _levelStats.value = refreshed

            achievementsManager.onAchievementUnlocked("first_flow")
            achievementsManager.onAchievementUnlocked("first_level")
            achievementsManager.onProgressUpdated("level_50", refreshed.size)
            if (moves <= level.optimalMoves) {
                achievementsManager.onAchievementUnlocked("optimal_win")
            }
            val pack0 = LevelPack.PACKS[0]
            val pack0Completed = pack0.levels.count { refreshed.containsKey(it.id) }
            if (pack0Completed >= pack0.levels.size) {
                achievementsManager.onAchievementUnlocked("all_5x5")
            }
        }
    }

    /** Daily wins record into the separate store, and extend the streak once the day is cleared. */
    private fun onDailyLevelWon(levelId: String, moves: Int, optimalMoves: Int) {
        val day = _dailyDay.value
        viewModelScope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                dailyRepository.updateBestScore(levelId, moves)
                dailyRepository.getLevelStats()
            }
            _dailyStats.value = refreshed

            achievementsManager.onAchievementUnlocked("first_flow")
            achievementsManager.onAchievementUnlocked("first_daily")
            if (moves <= optimalMoves) {
                achievementsManager.onAchievementUnlocked("optimal_win")
            }

            val dayComplete = (0 until DailyLevels.LEVELS_PER_DAY)
                .all { refreshed.containsKey(DailyLevels.levelId(day, it)) }
            if (dayComplete) {
                val streak = dailyStore.recordDayCompleted(day)
                achievementsManager.onProgressUpdated("daily_streak_7", streak.best.toInt())
                achievementsManager.onProgressUpdated("daily_streak_30", streak.best.toInt())
                DailyStreakReporter.report(getApplication(), "pipes", streak, day)
            }
        }
    }

    fun getCurrentMoves(): Int = _uiState.value.history.size

    override fun onUndo() {
        val s = _uiState.value
        if (s.history.isEmpty() || s.isLevelWon) return
        _uiState.update {
            it.copy(
                gameState = it.history.last(),
                history = it.history.dropLast(1),
                activeColor = null,
                activePath = emptyList()
            )
        }
    }

    override fun onRestart() {
        val s = _uiState.value
        if (s.history.isEmpty() || s.isLevelWon || s.packIndex == NO_PACK_INDEX) return
        _uiState.update {
            it.copy(
                gameState = PipesGameState(),
                history = emptyList(),
                isLevelWon = false,
                activeColor = null,
                activePath = emptyList()
            )
        }
    }

    fun dismissAchievementNotification() {
        achievementsManager.dismissNotification()
    }

    companion object {
        const val KEY_COLORBLIND = "pipes_colorblind"

        /** Namespace shared by the daily-challenge store and its reminder. */
        const val DAILY_KEY_PREFIX = "pipes_daily"

        private const val REMINDER_NOTIFICATION_ID = 5102

        /** Sentinel [PipesUiState.packIndex] for the date-generated daily pack. */
        const val DAILY_PACK_INDEX = -2

        /** Sentinel [PipesUiState.packIndex] for "no level loaded". */
        private const val NO_PACK_INDEX = -1

        /** Minimum path length for a backtrack step (needs a cell to step back from). */
        private const val BACKTRACK_MIN_LENGTH = 2
    }
}
