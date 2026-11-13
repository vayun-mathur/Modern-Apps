package com.vayunmathur.games.pipes.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.CompletedLevelsRepository
import com.vayunmathur.games.pipes.domain.BoardEdits
import com.vayunmathur.games.pipes.domain.BoardState
import com.vayunmathur.games.pipes.domain.DailyLevels
import com.vayunmathur.games.pipes.domain.StrokeRules
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

/** The board position the screens draw; the pure model lives in domain/. */
typealias PipesGameState = BoardState

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
        if (_uiState.value.isLevelWon) return
        // A stroke still open (its release was lost, or a second finger got through) is
        // abandoned, not stacked: the new stroke starts from the board as it was before it,
        // so the undo snapshot stays the real pre-stroke board.
        _uiState.update { cur ->
            if (cur.activeColor == null) {
                cur
            } else {
                cur.copy(
                    gameState = cur.preDrawState ?: cur.gameState,
                    activeColor = null,
                    activePath = emptyList(),
                    preDrawState = null,
                )
            }
        }
        val s = _uiState.value
        val levelData = s.levelData ?: return
        val endpointColor = levelData.endpoints.find { ep -> cell in ep.cells }?.colorIndex

        val base = s.gameState
        val (color, stroke) = if (endpointColor != null) {
            // Grabbing an endpoint always restarts that color: its current line breaks
            // right away (like grabbing a line partway does) so the drag is free to take
            // a different route. Releasing without drawing leaves the line cleared.
            endpointColor to listOf(cell)
        } else {
            val ownerColor = base.cellOwner[cell] ?: return
            val idx = base.paths[ownerColor]?.indexOf(cell) ?: -1
            if (idx < 0) return
            ownerColor to base.paths.getValue(ownerColor).take(idx + 1)
        }
        _uiState.update {
            it.copy(
                activeColor = color,
                activePath = stroke,
                gameState = BoardEdits.applyStroke(levelData, base, color, stroke),
                preDrawState = base,
            )
        }
    }

    /**
     * While a stroke is open, [PipesUiState.gameState] is a preview: the board from before
     * the stroke with the stroke applied, so other colours it crosses show as cut and come
     * back intact if the finger backs out again. Undo returns to [PipesUiState.preDrawState].
     */
    override fun extendPath(cell: CellPos) {
        val s = _uiState.value
        val activeColor = s.activeColor ?: return
        val levelData = s.levelData ?: return
        if (s.isLevelWon) return
        val base = s.preDrawState ?: s.gameState
        val next = StrokeRules.extend(levelData, base.cellOwner, activeColor, s.activePath, cell)
        if (next == s.activePath) return
        _uiState.update {
            it.copy(activePath = next, gameState = BoardEdits.applyStroke(levelData, base, activeColor, next))
        }
    }

    override fun commitDraw() {
        val s = _uiState.value
        val activeColor = s.activeColor ?: return
        val levelData = s.levelData ?: return
        if (s.isLevelWon) return

        val newPath = s.activePath
        val preDrawState = s.preDrawState ?: s.gameState
        val newGameState = BoardEdits.applyStroke(levelData, preDrawState, activeColor, newPath)
        // A stroke that only broke a line (released on its own start cell) counts as a move
        // only when it actually removed something.
        val counts = newPath.size >= 2 || newGameState != preDrawState

        _uiState.update {
            it.copy(
                gameState = newGameState,
                history = if (counts) it.history + preDrawState else it.history,
                activeColor = null,
                activePath = emptyList(),
                preDrawState = null
            )
        }
        if (newPath.size < 2) return

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
    }
}
