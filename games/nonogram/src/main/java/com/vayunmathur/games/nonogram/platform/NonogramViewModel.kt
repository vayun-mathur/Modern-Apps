package com.vayunmathur.games.nonogram.platform

import android.app.Application
import java.util.concurrent.atomic.AtomicLong
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.nonogram.data.DAILY_SIZE
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.NonogramDataStore
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.games.nonogram.data.NonogramPuzzle
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import com.vayunmathur.games.nonogram.data.StoredBoard
import com.vayunmathur.games.nonogram.data.sizeForLevel
import com.vayunmathur.games.nonogram.domain.NonogramGenerator
import com.vayunmathur.games.nonogram.domain.NonogramStrokes
import com.vayunmathur.games.nonogram.domain.Stroke
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * How many generation retries have been asked for. Part of the session so a retry reloads the
     * board; the attempt count only ever widens the seed search, never changes its start, so a
     * puzzle found on retry N is the same one a fresh process finds on its own retry N — which is
     * what keeps saved marks valid across a relaunch.
     */
    private val _generationAttempt = MutableStateFlow(0)

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
                _generationAttempt,
            ) { mode, level, markMode, streak, attempt -> Session(mode, level, markMode, streak, attempt) }
                .collectLatest { session -> load(session) }
        }
    }

    /** The stored inputs that decide which puzzle is on screen. */
    private data class Session(
        val mode: GameMode,
        val level: Int,
        val markMode: MarkMode,
        val streak: Long,
        val attempt: Int,
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
            // A retry keeps the same seed but searches more rounds: re-running the base search
            // would deterministically find the same nothing, while a fresh seed would orphan any
            // marks already saved against this level.
            val rounds = (
                NonogramGenerator.DEFAULT_ROUNDS +
                    session.attempt * NonogramGenerator.RETRY_EXTRA_ROUNDS
                ).coerceAtMost(MAX_GENERATION_ROUNDS)
            cached ?: NonogramGenerator.generateSeeded(size, seed, rounds)
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
     * Streams the stored marks onto [puzzle].
     *
     * This is how a board is *loaded*; after that the ViewModel's own copy is the source of truth and
     * moves are applied to it directly (see [commit]). While any write is still queued, an emission
     * for the board already on screen is a lagging echo of an earlier move and is ignored — applying
     * it would briefly roll the board back, and judging the next tap against it is how two quick
     * cross taps used to both add an X.
     *
     * Daily marks are blanked whenever the stored day is not the day being played, so a stale write
     * from yesterday can never show up on today's board even if the rollover edit was interrupted.
     */
    private suspend fun observeMarks(session: Session, puzzle: NonogramPuzzle, day: Long) {
        val daily = session.mode == GameMode.DAILY
        boardDay = day

        combine(dataStore.board(daily), dataStore.dailyDay, dailyStore.lastCompletedDayFlow) {
            board, storedDay, lastCompleted ->
            // Anything stored against a different day is yesterday's work and must not be shown.
            val stale = daily && storedDay != day
            (if (stale) StoredBoard() else board) to (lastCompleted == day)
        }.collectLatest { (board, dailyDone) ->
            val onScreen = _uiState.value.game
                ?.takeIf { hasPendingWrites && it.isBoardFor(session, puzzle) }
            val game = onScreen ?: NonogramGameState(
                puzzle = puzzle,
                filled = board.filled,
                crossed = board.crossed,
                revealedBlanks = board.revealed,
                hearts = board.hearts,
                mode = session.mode,
                level = session.level,
            )
            _uiState.value = _uiState.value.copy(
                game = game,
                generating = false,
                dailyDone = dailyDone,
            )
            if (game.isWon) onWin(game, daily, day)
        }
    }

    private fun NonogramGameState.isBoardFor(session: Session, puzzle: NonogramPuzzle): Boolean =
        this.puzzle == puzzle && mode == session.mode && level == session.level

    /** The daily the board on screen belongs to, so a queued write cannot land on the next day's. */
    private var boardDay: Long = Long.MIN_VALUE

    /**
     * Serialises every board write, so snapshots land in the order the moves were made and a level
     * change queued after them is never overtaken.
     */
    private val writeLock = Mutex()

    /** Tickets for queued and finished board writes; equal when nothing is waiting to be persisted. */
    private val requestedWrite = AtomicLong(0)
    private val completedWrite = AtomicLong(0)

    private val hasPendingWrites: Boolean get() = completedWrite.get() != requestedWrite.get()

    /**
     * Shows [next] at once and persists it in the background.
     *
     * The board on screen updates in the same frame as the tap instead of after a DataStore round
     * trip, and every later move is judged against this state. Writes are queued behind [writeLock];
     * one that has been superseded by a newer snapshot before it starts is skipped, so a burst of taps
     * costs one or two disk writes rather than one each.
     */
    private fun commit(next: NonogramGameState) {
        val previous = _uiState.value.game ?: return
        if (next == previous) return
        _uiState.value = _uiState.value.copy(game = next)
        persist(next)
        if (next.isWon) onWin(next, next.mode == GameMode.DAILY, boardDay)
    }

    private fun persist(game: NonogramGameState) {
        val ticket = requestedWrite.incrementAndGet()
        val board = StoredBoard(game.filled, game.crossed, game.revealedBlanks, game.hearts)
        val daily = game.mode == GameMode.DAILY
        val day = boardDay
        viewModelScope.launch {
            writeLock.withLock {
                if (ticket == requestedWrite.get()) dataStore.saveBoard(board, daily, game.level, day)
            }
            completedWrite.accumulateAndGet(ticket, ::maxOf)
        }
    }

    /**
     * Tap: places whichever mark [MarkMode] selects.
     *
     * In fill mode a cell that belongs to the picture is filled and one that does not costs a heart. In
     * cross mode a tap only ever writes the player's own note, so it is always free — which is the
     * point of the mode: crossing off a long run of cells should not be a gamble.
     */
    override fun tapCell(index: Int) {
        beginStroke(index, alternate = false)
        endStroke()
    }

    /**
     * Long press: the other mark from whatever the mode is.
     *
     * So a cross-mode player can still fill without switching back, and a fill-mode player can still
     * jot a cross. Crossing is free either way, even on a cell that does belong to the picture: a cross
     * is the player's own working, and being wrong in a note is not a guess the game should punish.
     */
    override fun crossCell(index: Int) {
        beginStroke(index, alternate = true)
        endStroke()
    }

    /** The stroke in progress, if a finger is down on the grid. See [NonogramStrokes]. */
    private var stroke: Stroke? = null

    override fun beginStroke(index: Int, alternate: Boolean) {
        val state = _uiState.value
        val game = state.game ?: return
        val mode = when {
            !alternate -> state.markMode
            state.markMode == MarkMode.FILL -> MarkMode.CROSS
            else -> MarkMode.FILL
        }
        val step = NonogramStrokes.begin(game, index, mode)
        stroke = step.stroke
        commit(step.game)
    }

    override fun extendStroke(index: Int) {
        val current = stroke ?: return
        val game = _uiState.value.game ?: return
        val step = NonogramStrokes.extend(game, current, index)
        stroke = step.stroke
        commit(step.game)
    }

    override fun endStroke() {
        stroke = null
    }

    override fun setMarkMode(mode: MarkMode) {
        // In memory first, so a tap straight after switching uses the new mode.
        _uiState.value = _uiState.value.copy(markMode = mode)
        viewModelScope.launch { dataStore.setMarkMode(mode) }
    }

    /**
     * Advances from the solved board on screen to the level after it.
     *
     * The target comes from the loaded game's own level, not [NonogramUiState.level]: `load()` raises
     * that counter as soon as the stored level changes, before the next puzzle exists, so a second
     * tap on Next used to read the already-raised number and skip a level. Marking the state as
     * generating straight away disables the button for the rest of the load, and the solved-board
     * guard means a stray tap that lands after the new puzzle appears does nothing.
     */
    override fun nextLevel() {
        val state = _uiState.value
        val game = state.game ?: return
        if (state.mode == GameMode.DAILY || game.mode == GameMode.DAILY) return
        if (state.generating || !game.isWon) return
        val target = game.level + 1
        _uiState.value = state.copy(generating = true)
        // Behind the write lock so the winning move's snapshot lands first.
        viewModelScope.launch { writeLock.withLock { dataStore.saveLevel(target) } }
    }

    /** Clears every mark and restores the hearts, on screen at once and on disk behind it. */
    override fun restartLevel() {
        val game = _uiState.value.game ?: return
        commit(
            game.copy(
                filled = emptySet(),
                crossed = emptySet(),
                revealedBlanks = emptySet(),
                hearts = STARTING_HEARTS,
            )
        )
    }

    /**
     * Retries generation after [NonogramUiState.generationFailed].
     *
     * Clearing marks cannot help here — no marks flow feeds the load, so `restartLevel` leaves the
     * failed screen stuck — and only a bump of [_generationAttempt] re-runs the search.
     */
    override fun retryGeneration() {
        _generationAttempt.value += 1
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

        /**
         * Caps the widening retry search: eight attempts of [NonogramGenerator.RETRY_EXTRA_ROUNDS].
         * Each round is 60 solver checks over at most a 15x15 grid, so this bounds one retry at a
         * few seconds on [Dispatchers.Default] with the spinner showing, instead of growing a
         * longer search on every tap of Try again.
         */
        private const val MAX_GENERATION_ROUNDS = 64
    }
}
