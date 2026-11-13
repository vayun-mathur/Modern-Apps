package com.vayunmathur.games.unblockjam.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.unblockjam.data.CompletedLevelsRepository
import com.vayunmathur.games.unblockjam.data.DailyLevelGenerator
import com.vayunmathur.games.unblockjam.data.InProgressBoards
import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.data.LevelPack
import com.vayunmathur.library.util.LevelStats
import com.vayunmathur.library.util.DailyChallengeStore
import com.vayunmathur.library.util.DailyStreakReporter
import com.vayunmathur.library.work.DailyPuzzleReminder
import com.vayunmathur.library.work.DailyReminderSettings
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

/**
 * UI state for the active game screen.
 *
 * [currentLevelData] is null until [UnblockJamViewModel.loadLevel] has been
 * called for the current ([packIndex], [levelIndex]).
 */
data class UnblockJamUiState(
    val packIndex: Int = -1,
    val levelIndex: Int = -1,
    val currentLevelData: LevelData? = null,
    val history: List<LevelData> = emptyList(),
    val isLevelWon: Boolean = false,
)

/**
 * ViewModel for the UnblockJam game.
 *
 * Owns:
 *  - Current level data + move history + win state
 *  - Persistent level stats (best scores, total moves, undo count) via
 *    [CompletedLevelsRepository]
 *  - The [UnblockJamAchievementsManager] instance and existing-achievement check
 *
 * Composables keep only purely-visual state: the in-flight drag offsets,
 * dialog visibility, and the slide-out animation for the main block when
 * a level is won.
 */
class UnblockJamViewModel(application: Application) : AndroidViewModel(application), GameActions {

    val repository: CompletedLevelsRepository = CompletedLevelsRepository(application)

    /** Boards left part-way, restored when their level is opened again. */
    private val inProgress = InProgressBoards(application)

    val achievementsManager: UnblockJamAchievementsManager = run {
        val json = application.assets.open("achievements.json")
            .bufferedReader().use { it.readText() }
        UnblockJamAchievementsManager(application, json, repository)
    }

    private val _uiState = MutableStateFlow(UnblockJamUiState())
    val uiState: StateFlow<UnblockJamUiState> = _uiState.asStateFlow()

    private val _levelStats =
        MutableStateFlow<Map<String, LevelStats>>(repository.getLevelStats())
    val levelStats: StateFlow<Map<String, LevelStats>> = _levelStats.asStateFlow()

    // ---- Daily challenge ----

    /**
     * Daily scores live in their own prefs file, pruned to the current day. They must not land in
     * [repository], whose map size feeds the `level_50` achievement — five new level IDs a day
     * would inflate it.
     */
    private val dailyRepository = LevelStatsRepository(application, "daily_stats")

    private val dailyStore = DailyChallengeStore(application, DAILY_KEY_PREFIX)

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
            (0 until DailyLevelGenerator.LEVELS_PER_DAY)
                .count { stats.containsKey(DailyLevelGenerator.levelId(day, it)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        viewModelScope.launch {
            achievementsManager.checkExistingAchievements()
        }
    }

    /**
     * Generates the day's pack off the main thread. Solving every candidate is expensive, so this
     * runs only when the player actually opens the daily — the pack-list card derives its progress
     * from the level IDs alone.
     */
    fun refreshDaily() {
        viewModelScope.launch(Dispatchers.Default) {
            val today = dailyStore.todayEpochDay()
            if (_dailyDay.value == today && _dailyPack.value != null) return@launch
            _dailyDay.value = today
            withContext(Dispatchers.IO) {
                // Only today's pack is ever playable, so yesterday's scores are dead weight.
                dailyRepository.retainOnly(todayLevelIds(today))
                inProgress.retainDaily(todayLevelIds(today))
                _dailyStats.value = dailyRepository.getLevelStats()
            }
            _dailyPack.value = DailyLevelGenerator.packFor(today)
        }
    }

    private fun todayLevelIds(day: Long): Set<String> =
        (0 until DailyLevelGenerator.LEVELS_PER_DAY)
            .mapTo(mutableSetOf()) { DailyLevelGenerator.levelId(day, it) }

    /** Resolves a pack index to its levels, covering both the shipped packs and the daily pack. */
    private fun levelsFor(packIndex: Int): List<LevelData>? = when (packIndex) {
        DAILY_PACK_INDEX -> _dailyPack.value?.levels
        in LevelPack.PACKS.indices -> LevelPack.PACKS[packIndex].levels
        else -> null
    }

    /**
     * Loads the requested level if it differs from the currently-loaded one.
     * Reloads even the same level when it was already won, so re-entering a
     * solved level from the selector restarts it instead of showing a frozen
     * won board (gestures and Undo/Restart are all disabled once won).
     * Safe to call from a [androidx.compose.runtime.LaunchedEffect] keyed on
     * ([packIndex], [levelIndex]).
     *
     * A level left part-way - by Back, by switching puzzles, or by the process
     * being killed - comes back as it was left, undo history included.
     */
    fun loadLevel(packIndex: Int, levelIndex: Int) {
        if (isPlaying(packIndex, levelIndex)) return
        val levelData = levelsFor(packIndex)?.getOrNull(levelIndex) ?: return
        val saved = inProgress.load(levelData)
        _uiState.value = UnblockJamUiState(
            packIndex = packIndex,
            levelIndex = levelIndex,
            currentLevelData = saved?.current ?: levelData,
            history = saved?.history.orEmpty(),
        )
    }

    /** Whether ([packIndex], [levelIndex]) is already on screen and still being played. */
    private fun isPlaying(packIndex: Int, levelIndex: Int): Boolean {
        val current = _uiState.value
        val sameLevel = current.packIndex == packIndex && current.levelIndex == levelIndex
        return sameLevel && current.currentLevelData != null && !current.isLevelWon
    }

    /** Writes the board on screen to [inProgress], or forgets it once it is back at the start. */
    private fun saveProgress() {
        val s = _uiState.value
        val current = s.currentLevelData ?: return
        if (s.isLevelWon) return
        if (s.history.isEmpty()) inProgress.clear(current.id) else inProgress.save(current, s.history)
    }

    /** Move count for the active attempt, including the winning move if applicable. */
    fun getCurrentMoves(): Int {
        val s = _uiState.value
        val winningMoveIncrement =
            if (s.isLevelWon && s.currentLevelData?.lastMovedBlockIndex != 0) 1 else 0
        return s.history.size + winningMoveIncrement
    }

    override fun onBlockMoved(newLevelData: LevelData) {
        val s = _uiState.value
        val current = s.currentLevelData ?: return
        // Block moved back to its previous position — collapse with last history entry.
        if (s.history.isNotEmpty() && s.history.last().blocks == newLevelData.blocks) {
            _uiState.update {
                it.copy(
                    currentLevelData = it.history.last(),
                    history = it.history.dropLast(1),
                )
            }
            saveProgress()
            return
        }
        if (s.isLevelWon) return

        if (newLevelData.lastMovedBlockIndex != current.lastMovedBlockIndex) {
            _uiState.update {
                it.copy(
                    currentLevelData = newLevelData,
                    history = it.history + current,
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                repository.incrementTotalMoves()
                achievementsManager.onProgressUpdated(
                    "moves_1000", repository.getTotalMoves(),
                )
            }
        } else {
            _uiState.update { it.copy(currentLevelData = newLevelData) }
        }
        saveProgress()
    }

    override fun onLevelWon() {
        val s = _uiState.value
        if (s.isLevelWon || s.packIndex == NO_PACK_INDEX) return
        _uiState.update { it.copy(isLevelWon = true) }

        val level = s.currentLevelData ?: return
        inProgress.clear(level.id)
        val moves = getCurrentMoves()

        if (s.packIndex == DAILY_PACK_INDEX) {
            onDailyLevelWon(level, moves)
            return
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.updateBestScore(level.id, moves)
            }
            val refreshed = withContext(Dispatchers.IO) { repository.getLevelStats() }
            _levelStats.value = refreshed

            achievementsManager.onAchievementUnlocked("first_level")
            achievementsManager.onProgressUpdated("level_50", refreshed.size)
            if (moves <= level.optimalMoves) {
                achievementsManager.onAchievementUnlocked("optimal_win")
            }
            achievementsManager.onPackCompletionChanged(refreshed.keys)
        }
    }

    /** Daily wins record into the separate store, and extend the streak once the day is cleared. */
    private fun onDailyLevelWon(level: LevelData, moves: Int) {
        val day = _dailyDay.value
        viewModelScope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                dailyRepository.updateBestScore(level.id, moves)
                dailyRepository.getLevelStats()
            }
            _dailyStats.value = refreshed

            achievementsManager.onAchievementUnlocked("first_daily")
            if (moves <= level.optimalMoves) {
                achievementsManager.onAchievementUnlocked("optimal_win")
            }

            val dayComplete = (0 until DailyLevelGenerator.LEVELS_PER_DAY)
                .all { refreshed.containsKey(DailyLevelGenerator.levelId(day, it)) }
            if (dayComplete) {
                val streak = dailyStore.recordDayCompleted(day)
                achievementsManager.onProgressUpdated("daily_streak_7", streak.best.toInt())
                achievementsManager.onProgressUpdated("daily_streak_30", streak.best.toInt())
                DailyStreakReporter.report(getApplication(), "unblockjam", streak, day)
            }
        }
    }

    override fun onUndo() {
        val s = _uiState.value
        if (s.history.isEmpty() || s.isLevelWon) return
        _uiState.update {
            it.copy(
                currentLevelData = it.history.last(),
                history = it.history.dropLast(1),
            )
        }
        saveProgress()
        viewModelScope.launch(Dispatchers.IO) {
            repository.incrementUndoCount()
            achievementsManager.onProgressUpdated(
                "undo_master", repository.getUndoCount(),
            )
        }
    }

    override fun onRestart() {
        val s = _uiState.value
        if (s.history.isEmpty() || s.isLevelWon || s.packIndex == NO_PACK_INDEX) return
        val pristine = levelsFor(s.packIndex)?.getOrNull(s.levelIndex) ?: return
        _uiState.update {
            it.copy(
                currentLevelData = pristine,
                history = emptyList(),
                isLevelWon = false,
            )
        }
        inProgress.clear(pristine.id)
    }

    fun dismissAchievementNotification() {
        achievementsManager.dismissNotification()
    }

    companion object {
        /** Namespace shared by the daily-challenge store and its reminder. */
        const val DAILY_KEY_PREFIX = "unblockjam_daily"

        private const val REMINDER_NOTIFICATION_ID = 5103

        /** Sentinel [UnblockJamUiState.packIndex] for the date-generated daily pack. */
        const val DAILY_PACK_INDEX = -2

        /** Sentinel [UnblockJamUiState.packIndex] for "no level loaded". */
        private const val NO_PACK_INDEX = -1
    }
}
