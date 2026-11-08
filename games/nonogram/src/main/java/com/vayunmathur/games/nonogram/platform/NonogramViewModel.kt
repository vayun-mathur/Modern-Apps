package com.vayunmathur.games.nonogram.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.nonogram.data.DAILY_SIZE
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.NonogramDataStore
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.games.nonogram.data.NonogramPuzzle
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import com.vayunmathur.games.nonogram.data.sizeForLevel
import com.vayunmathur.games.nonogram.domain.NonogramGenerator
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DailyChallengeStore
import com.vayunmathur.library.util.DailyStreakReporter
import com.vayunmathur.library.work.DailyPuzzleReminder
import com.vayunmathur.library.work.DailyReminderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the puzzle on screen and the progress behind it.
 *
 * Casual mode is a single level counter: finishing writes `level + 1` and clears the board in one
 * DataStore edit, so there is no level list and no way back. Daily mode is one puzzle per calendar
 * day, generated from the date, with its own marks stamped with the day they belong to.
 *
 * Generation runs on [Dispatchers.Default]: the generator paints candidates and rejects any the line
 * solver cannot finish, which is fast but not free, and a 15x15 should not stutter the board.
 */
class NonogramViewModel(application: Application) : AndroidViewModel(application),
    NonogramGameActions, SettingsActions {

    val dataStore = NonogramDataStore(application)
    private val dailyStore = DailyChallengeStore(application, DAILY_KEY_PREFIX)
    private val reminderSettings = DailyReminderSettings(application, DAILY_KEY_PREFIX)

    private val _uiState = MutableStateFlow(NonogramUiState())
    val uiState: StateFlow<NonogramUiState> = _uiState.asStateFlow()

    /** Cached per (size, seed) so flipping modes back and forth does not regenerate. */
    private var casualPuzzle: Pair<Int, NonogramPuzzle>? = null
    private var dailyPuzzle: Pair<Long, NonogramPuzzle>? = null

    val reminderEnabled: StateFlow<Boolean> = reminderSettings.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val reminderMinutesOfDay: StateFlow<Long> = reminderSettings.minutesOfDay
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DailyPuzzleReminder.DEFAULT_MINUTES_OF_DAY,
        )

    val achievementsManager: AchievementsManager = run {
        val json = application.assets.open("achievements.json").bufferedReader().readText()
        NonogramAchievementsManager(application, json, dataStore, dailyStore)
    }

    init {
        achievementsManager.checkExistingAchievements()

        // One collector drives the whole screen: whichever of mode, level or marks changes, the
        // board is rebuilt from the stored state rather than mutated in place.
        viewModelScope.launch {
            combine(
                dataStore.gameMode,
                dataStore.currentLevel,
                dataStore.markMode,
                dailyStore.currentStreak,
            ) { mode, level, markMode, streak -> Session(mode, level, markMode, streak) }
                .collectLatest { session -> load(session) }
        }
    }

    /** The stored inputs that decide which puzzle is on screen. */
    private data class Session(
        val mode: GameMode,
        val level: Int,
        val markMode: MarkMode,
        val streak: Long,
    )

    private suspend fun load(session: Session) {
        val daily = session.mode == GameMode.DAILY
        val day = dailyStore.todayEpochDay()
        if (daily) dataStore.ensureDailyDay(day)

        _uiState.value = _uiState.value.copy(
            mode = session.mode,
            level = session.level,
            markMode = session.markMode,
            dailyStreak = session.streak,
            generating = true,
            generationFailed = false,
        )

        val size = if (daily) DAILY_SIZE else sizeForLevel(session.level)
        val seed = if (daily) DAILY_SEED_OFFSET + day else session.level.toLong()

        val puzzle = withContext(Dispatchers.Default) {
            val cached = if (daily) dailyPuzzle?.takeIf { it.first == day }?.second
            else casualPuzzle?.takeIf { it.first == session.level }?.second
            cached ?: NonogramGenerator.generateSeeded(size, seed)
        }

        if (puzzle == null) {
            _uiState.value = _uiState.value.copy(generating = false, generationFailed = true)
            return
        }
        if (daily) dailyPuzzle = day to puzzle else casualPuzzle = session.level to puzzle

        // Marks are collected separately so a tap redraws without regenerating the puzzle.
        observeMarks(session, puzzle, day)
    }

    /**
     * Streams the player's marks onto [puzzle].
     *
     * Daily marks are blanked whenever the stored day is not the day being played, so a stale write
     * from yesterday can never show up on today's board even if the rollover edit was interrupted.
     */
    private suspend fun observeMarks(session: Session, puzzle: NonogramPuzzle, day: Long) {
        val daily = session.mode == GameMode.DAILY
        val filledFlow = if (daily) dataStore.dailyFilledCells else dataStore.filledCells
        val crossedFlow = if (daily) dataStore.dailyCrossedCells else dataStore.crossedCells
        val revealedFlow = if (daily) dataStore.dailyRevealedBlanks else dataStore.revealedBlanks
        val heartsFlow = if (daily) dataStore.dailyHearts else dataStore.hearts

        // Two stages because the typed `combine` overloads stop at five flows.
        val boardFlow = combine(filledFlow, crossedFlow, revealedFlow, heartsFlow) {
            filled, crossed, revealed, hearts ->
            Marks(filled, crossed, revealed, hearts, dailyDone = false)
        }

        combine(boardFlow, dataStore.dailyDay, dailyStore.lastCompletedDayFlow) {
            marks, storedDay, lastCompleted ->
            // Anything stored against a different day is yesterday's work and must not be shown.
            val stale = daily && storedDay != day
            if (stale) {
                Marks(emptySet(), emptySet(), emptySet(), STARTING_HEARTS, lastCompleted == day)
            } else {
                marks.copy(dailyDone = lastCompleted == day)
            }
        }.collectLatest { marks ->
            val game = NonogramGameState(
                puzzle = puzzle,
                filled = marks.filled,
                crossed = marks.crossed,
                revealedBlanks = marks.revealed,
                hearts = marks.hearts,
                mode = session.mode,
                level = session.level,
            )
            _uiState.value = _uiState.value.copy(
                game = game,
                generating = false,
                dailyDone = marks.dailyDone,
            )
            if (game.isWon) onWin(game, daily, day)
        }
    }

    private data class Marks(
        val filled: Set<Int>,
        val crossed: Set<Int>,
        val revealed: Set<Int>,
        val hearts: Int,
        val dailyDone: Boolean,
    )

    /**
     * Tap: places whichever mark [MarkMode] selects.
     *
     * In fill mode a cell that belongs to the picture is filled and one that does not costs a heart. In
     * cross mode a tap only ever writes the player's own note, so it is always free — which is the
     * point of the mode: crossing off a long run of cells should not be a gamble.
     */
    override fun tapCell(index: Int) {
        val game = _uiState.value.game ?: return
        if (game.isOver || game.isLocked(index)) return
        if (_uiState.value.markMode == MarkMode.CROSS) {
            toggleNote(game, index)
            return
        }

        val daily = game.mode == GameMode.DAILY
        viewModelScope.launch {
            if (game.belongsToPicture(index)) {
                dataStore.fillCell(index, daily)
            } else {
                dataStore.revealBlank(index, daily)
            }
        }
    }

    /**
     * Long press: the other mark from whatever the mode is.
     *
     * So a cross-mode player can still fill without switching back, and a fill-mode player can still
     * jot a cross. Crossing is free either way, even on a cell that does belong to the picture: a cross
     * is the player's own working, and being wrong in a note is not a guess the game should punish.
     */
    override fun crossCell(index: Int) {
        val game = _uiState.value.game ?: return
        if (game.isOver || game.isLocked(index)) return
        if (_uiState.value.markMode == MarkMode.CROSS) {
            val daily = game.mode == GameMode.DAILY
            viewModelScope.launch {
                if (game.belongsToPicture(index)) {
                    dataStore.fillCell(index, daily)
                } else {
                    dataStore.revealBlank(index, daily)
                }
            }
        } else {
            toggleNote(game, index)
        }
    }

    private fun toggleNote(game: NonogramGameState, index: Int) {
        val daily = game.mode == GameMode.DAILY
        val alreadyNoted = index in game.crossed
        viewModelScope.launch { dataStore.setNote(index, !alreadyNoted, daily) }
    }

    override fun setMarkMode(mode: MarkMode) {
        viewModelScope.launch { dataStore.setMarkMode(mode) }
    }

    override fun nextLevel() {
        val state = _uiState.value
        if (state.mode == GameMode.DAILY) return
        viewModelScope.launch { dataStore.saveLevel(state.level + 1) }
    }

    override fun restartLevel() {
        val daily = _uiState.value.mode == GameMode.DAILY
        viewModelScope.launch { dataStore.clearMarks(daily) }
    }

    override fun setGameMode(mode: GameMode) {
        viewModelScope.launch { dataStore.setGameMode(mode) }
    }

    override fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            reminderSettings.setEnabled(enabled)
            rescheduleReminder(enabled, reminderMinutesOfDay.value)
        }
    }

    override fun setReminderTime(hour: Int, minute: Int) {
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

    fun dismissAchievementNotification() = achievementsManager.dismissNotification()

    /**
     * The one place a completed puzzle is recorded.
     *
     * Reached from the marks collector, so it can fire more than once for the same win as unrelated
     * flows re-emit. Everything it touches is idempotent for a given day: [DailyChallengeStore] treats
     * a repeat of the same day as a no-op, and unlocking an already-unlocked achievement does nothing.
     * The lifetime counter is banked under the win key in the store, so even a relaunch replaying an
     * already-recorded win cannot count it twice; [recordedWin] is just the in-memory fast path.
     */
    private fun onWin(game: NonogramGameState, daily: Boolean, day: Long) {
        // Daily wins are keyed by day and casual wins by negated level, which cannot collide.
        val key = if (daily) day else -game.level.toLong()
        if (recordedWin == key) return
        recordedWin = key

        viewModelScope.launch {
            val total = dataStore.recordCompletedOnce(key)
            val manager = achievementsManager
            manager.onAchievementUnlocked("first_puzzle")
            if (game.size >= BIG_PICTURE_SIZE) manager.onAchievementUnlocked("big_picture")
            if (game.hearts == STARTING_HEARTS) manager.onAchievementUnlocked("no_mistakes")
            manager.onProgressUpdated("puzzles_10", total)
            manager.onProgressUpdated("puzzles_50", total)

            if (daily) {
                manager.onAchievementUnlocked("first_daily")
                val streak = dailyStore.recordDayCompleted(day)
                DailyStreakReporter.report(getApplication(), "nonogram", streak, day)
                manager.onProgressUpdated("daily_streak_7", streak.current.toInt())
                manager.onProgressUpdated("daily_streak_30", streak.current.toInt())
            }
        }
    }

    /** Which win has already been banked, so a re-emitted flow cannot double-count it. */
    private var recordedWin: Long? = null

    companion object {
        /** Namespace shared by the daily-challenge store and its reminder. */
        const val DAILY_KEY_PREFIX = "nonogram_daily"

        private const val REMINDER_NOTIFICATION_ID = 5104

        /**
         * Keeps date-derived daily seeds away from the level-number seeds casual mode uses, and away
         * from the offsets the other games picked (500M pipes, 700M wordmaker, 900M unblockjam).
         */
        private const val DAILY_SEED_OFFSET = 1_100_000_000L

        /** Matches the "15x15" wording of the `big_picture` description. */
        private const val BIG_PICTURE_SIZE = 15
    }
}
