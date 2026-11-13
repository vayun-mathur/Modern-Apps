package com.vayunmathur.games.arrows.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.arrows.data.ArrowsDataStore
import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.ArrowsPuzzle
import com.vayunmathur.games.arrows.data.DAILY_ARROWS
import com.vayunmathur.games.arrows.data.DAILY_BOARD
import com.vayunmathur.games.arrows.data.DAILY_MIRRORS
import com.vayunmathur.games.arrows.data.GameMode
import com.vayunmathur.games.arrows.data.STARTING_HEARTS
import com.vayunmathur.games.arrows.data.TapOutcome
import com.vayunmathur.games.arrows.data.arrowCountForLevel
import com.vayunmathur.games.arrows.data.boardSizeForLevel
import com.vayunmathur.games.arrows.data.mirrorCountForLevel
import com.vayunmathur.games.arrows.domain.ArrowsGenerator
import com.vayunmathur.games.arrows.domain.ArrowsRules
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
 * Owns the board on screen and the progress behind it.
 *
 * Casual mode is a single level counter: clearing a board writes `level + 1` and resets it in one
 * DataStore edit, so there is no level list and no way back. Daily mode is one board per calendar day,
 * generated from the date, with its own progress stamped with the day it belongs to.
 *
 * Generation runs on [Dispatchers.Default]: building a board means repeatedly placing an arrow and
 * simulating its escape, which is fast but not free.
 */
class ArrowsViewModel(application: Application) : AndroidViewModel(application),
    ArrowsGameActions, SettingsActions {

    val dataStore = ArrowsDataStore(application)

    /**
     * One lane for every write to the game's stores, in the order they were made.
     *
     * DataStore runs an edit's transform in the caller's context while it holds the file lock, so
     * an edit launched from the main dispatcher holds that lock until the main thread gets round to
     * it. Writing from a single background lane keeps writes off the main thread, keeps them in tap
     * order (a later tap's progress can never land before an earlier one's), and means nothing
     * waiting on the main thread can wedge the store.
     */
    private val storeWrites = Dispatchers.IO.limitedParallelism(1)
    private val dailyStore = DailyChallengeStore(application, DAILY_KEY_PREFIX)
    private val reminderSettings = DailyReminderSettings(application, DAILY_KEY_PREFIX)

    private val _uiState = MutableStateFlow(ArrowsUiState())
    val uiState: StateFlow<ArrowsUiState> = _uiState.asStateFlow()

    /** Cached so flipping modes back and forth does not rebuild an identical board. */
    private var casualBoard: Pair<Int, ArrowsPuzzle>? = null
    private var dailyBoard: Pair<Long, ArrowsPuzzle>? = null

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
        ArrowsAchievementsManager(application, json, dataStore, dailyStore)
    }

    /**
     * Bumped to make [load] run again after a failed generation.
     *
     * Nothing else re-triggers it: a failed build leaves no progress collector running, so the
     * error screen's retry has to ask for a fresh pass explicitly. Feeding the count through the
     * same session flow keeps exactly one load in flight, since `collectLatest` cancels the
     * previous one. The count also salts the generation seed (see [buildBoard]): generation is
     * deterministic per seed, so retrying with the same seed would fail identically forever.
     */
    private val retryCount = MutableStateFlow(0)

    /**
     * Mirrors [ArrowsDataStore.heartHintSeen], read when a tap is blocked. Declared before `init`,
     * whose collector writes it, so its initializer cannot run after the first stored value lands.
     */
    private var heartHintSeen = true

    init {
        achievementsManager.checkExistingAchievements()

        viewModelScope.launch {
            combine(
                dataStore.gameMode,
                dataStore.currentLevel,
                dataStore.showRoutes,
                dailyStore.currentStreak,
                retryCount,
            ) { mode, level, showRoutes, streak, retries ->
                Session(mode, level, showRoutes, streak, retries)
            }
                .collectLatest { session -> load(session) }
        }
        viewModelScope.launch {
            dataStore.redirectorHintSeen.collect { seen ->
                _uiState.value = _uiState.value.copy(redirectorHintSeen = seen)
            }
        }
        viewModelScope.launch {
            dataStore.heartHintSeen.collect { heartHintSeen = it }
        }
    }

    /** The stored inputs that decide which board is on screen. */
    private data class Session(
        val mode: GameMode,
        val level: Int,
        val showRoutes: Boolean,
        val streak: Long,
        val retries: Int = 0,
    )

    private suspend fun load(session: Session) {
        val daily = session.mode == GameMode.DAILY
        val day = dailyStore.todayEpochDay()
        if (daily) withContext(storeWrites) { dataStore.ensureDailyDay(day) }

        _uiState.value = _uiState.value.copy(
            mode = session.mode,
            level = session.level,
            showRoutes = session.showRoutes,
            dailyStreak = session.streak,
            generating = true,
            generationFailed = false,
        )

        val puzzle = withContext(Dispatchers.Default) {
            val cached = if (daily) dailyBoard?.takeIf { it.first == day }?.second
            else casualBoard?.takeIf { it.first == session.level }?.second
            cached ?: buildBoard(daily, session.level, day, session.retries)
        }

        if (puzzle == null) {
            _uiState.value = _uiState.value.copy(generating = false, generationFailed = true)
            return
        }
        if (daily) dailyBoard = day to puzzle else casualBoard = session.level to puzzle

        observeProgress(session, puzzle, day)
    }

    /**
     * [retries] salts the seed: the first attempt for a level or day always uses retry zero, so
     * every player sees the same board, and only a board that failed to generate at all — which no
     * player ever saw — is rebuilt from a different seed on retry.
     */
    private fun buildBoard(daily: Boolean, level: Int, day: Long, retries: Int): ArrowsPuzzle? =
        if (daily) {
            val (cols, rows) = DAILY_BOARD
            ArrowsGenerator.generateSeeded(
                cols = cols,
                rows = rows,
                targetPieces = DAILY_ARROWS,
                mirrorCount = DAILY_MIRRORS,
                seed = DAILY_SEED_OFFSET + day + retries * RETRY_SEED_STRIDE,
            )
        } else {
            val (cols, rows) = boardSizeForLevel(level)
            ArrowsGenerator.generateSeeded(
                cols = cols,
                rows = rows,
                targetPieces = arrowCountForLevel(level),
                mirrorCount = mirrorCountForLevel(level),
                seed = level.toLong() + retries * RETRY_SEED_STRIDE,
            )
        }

    /**
     * Streams the player's progress onto [puzzle].
     *
     * Daily progress is discarded whenever the stored day is not the day being played, so a stale
     * write from yesterday can never show up on today's board even if the rollover edit was
     * interrupted.
     */
    private suspend fun observeProgress(session: Session, puzzle: ArrowsPuzzle, day: Long) {
        val daily = session.mode == GameMode.DAILY
        boardDay = day
        val removedFlow = if (daily) dataStore.dailyRemoved else dataStore.removedArrows
        val heartsFlow = if (daily) dataStore.dailyHearts else dataStore.hearts

        // A fresh board starts with nothing of ours in flight; see [expectedProgress].
        expectedProgress = null
        combine(removedFlow, heartsFlow, dataStore.dailyDay, dailyStore.lastCompletedDayFlow) {
            removed, hearts, storedDay, lastCompleted ->
            val stale = daily && storedDay != day
            Progress(
                removed = if (stale) emptySet() else removed,
                hearts = if (stale) STARTING_HEARTS else hearts,
                dailyDone = lastCompleted == day,
            )
        }.collectLatest { progress ->
            // Ids that no longer exist are dropped: a board rebuilt after a code change could
            // otherwise be considered finished by leftover progress that does not match it.
            val valid = progress.removed.filterTo(mutableSetOf()) { id ->
                puzzle.pieces.any { it.id == id }
            }
            val expected = expectedProgress
            if (expected != null) {
                if (expected != (valid to progress.hearts)) {
                    // An echo of an earlier write while a later one is still on its way: the
                    // board on screen is already ahead of it, so showing it would put an arrow
                    // that has just flown back on the board for a frame.
                    _uiState.value = _uiState.value.copy(dailyDone = progress.dailyDone)
                    return@collectLatest
                }
                expectedProgress = null
            }
            val game = ArrowsGameState(
                puzzle = puzzle,
                removed = valid,
                hearts = progress.hearts,
                level = session.level,
                mode = session.mode,
                blockedId = lastBlockedId,
            )
            _uiState.value = _uiState.value.copy(
                game = game,
                generating = false,
                dailyDone = progress.dailyDone,
            )
            if (game.isWon) onCleared(game, daily, day)
        }
    }

    private data class Progress(val removed: Set<Int>, val hearts: Int, val dailyDone: Boolean)

    /**
     * The arrow the last tap could not move.
     *
     * Kept here rather than persisted: it is a momentary bit of feedback about the tap just made, and
     * would be meaningless after a relaunch.
     */
    private var lastBlockedId: Int = -1

    /** The day the board on screen was loaded for, which is what a daily clear is banked under. */
    private var boardDay: Long = 0

    /** Source of [ArrowMove.id]. */
    private var nextMoveId = 1L

    /**
     * The progress our latest write will leave in the store, until the store reports it.
     *
     * Taps are applied on screen at once and written behind, so a quick second tap is shown before
     * the first one's write has echoed back through the progress flow. Echoes that do not match
     * this are stale and skipped; the one that does clears it.
     */
    private var expectedProgress: Pair<Set<Int>, Int>? = null

    /**
     * Applies a tap at once and hands it to the board to animate.
     *
     * The outcome is decided and committed now, from the board as it stands, so a second tap made
     * while the first arrow is still flying is judged against a board that no longer has it: arrows
     * can be launched as fast as the player can find them, each animating on its own. Committing at
     * tap time rather than queueing the tap was chosen because a queue only hides the wait — the
     * second arrow would still not move until the first had landed — while concurrent moves need no
     * waiting at all and cannot disagree with what is drawn, since the board draws exactly the state
     * the next tap is judged against.
     *
     * A tap on an arrow that is already animating is ignored, so a double tap on a blocked arrow
     * still costs one heart, not two.
     */
    override fun tapArrow(pieceId: Int) {
        val state = _uiState.value
        val game = state.game ?: return
        if (game.isOver || state.moves.any { it.pieceId == pieceId }) return
        val piece = game.remaining.firstOrNull { it.id == pieceId } ?: return

        val travel = ArrowsRules.travel(game, piece)
        val (next, outcome) = ArrowsRules.tap(game, pieceId)
        if (outcome == TapOutcome.IGNORED) return

        lastBlockedId = next.blockedId
        // The first heart ever lost is explained once; nothing else on the board says why it went.
        val explainHeart = outcome == TapOutcome.BLOCKED && !heartHintSeen
        if (explainHeart) heartHintSeen = true
        val move = ArrowMove(
            pieceId = pieceId,
            route = travel.route,
            advance = travel.advance,
            clears = travel.clears,
            id = nextMoveId++,
        )
        _uiState.value = state.copy(
            game = next,
            moves = state.moves + move,
            showHeartHint = state.showHeartHint || explainHeart,
        )

        val daily = state.mode == GameMode.DAILY
        val day = boardDay
        expectedProgress = next.removed to next.hearts
        viewModelScope.launch(storeWrites) {
            dataStore.saveProgress(next.removed, next.hearts, daily)
            if (explainHeart) dataStore.markHeartHintSeen()
            if (outcome == TapOutcome.BLOCKED) {
                achievementsManager.onAchievementUnlocked("first_block")
            }
        }
        // The progress collector would see the win too, but it skips echoes it is not expecting;
        // [onCleared] is exactly-once whichever path reaches it first.
        if (next.isWon) onCleared(next, daily, day)
    }

    /** Retires a finished animation. The move itself was applied when the arrow was tapped. */
    override fun finishMove(moveId: Long) {
        val state = _uiState.value
        if (state.moves.none { it.id == moveId }) return
        _uiState.value = state.copy(moves = state.moves.filterNot { it.id == moveId })
    }

    /**
     * Asks for a fresh generation pass after [load] found no board.
     *
     * Goes through [retryCount] rather than calling [load] directly, so the session collector
     * stays the only place loads run and can cancel this one like any other if the session
     * changes underneath it.
     */
    override fun retryGeneration() {
        retryCount.value += 1
    }

    /**
     * Moves on from the cleared board on screen.
     *
     * The next level is worked out from the board actually loaded ([ArrowsGameState.level]), not
     * from [ArrowsUiState.level]: [load] bumps the latter as soon as the new level is requested,
     * while the cleared board — and its button — are still showing, so a quick second tap used to
     * read the already-advanced level and skip one. Counting from the loaded board makes a repeat
     * tap write the same level again, and the guard ignores taps once loading has started or the
     * board on screen is not a cleared one.
     */
    override fun nextLevel() {
        val state = _uiState.value
        if (state.mode == GameMode.DAILY || state.generating) return
        val game = state.game ?: return
        if (!game.isWon) return
        lastBlockedId = -1
        _uiState.value = state.copy(moves = emptyList(), showHeartHint = false)
        viewModelScope.launch(storeWrites) { dataStore.saveLevel(game.level + 1) }
    }

    override fun restartLevel() {
        val state = _uiState.value
        val daily = state.mode == GameMode.DAILY
        lastBlockedId = -1
        // Reset on screen now rather than waiting for the store to echo it, for the same reason
        // taps are applied at once: a tap made in between must be judged against the fresh board.
        val reset = state.game?.let(ArrowsRules::resetAfterFailure)
        _uiState.value = state.copy(game = reset ?: state.game, moves = emptyList(), showHeartHint = false)
        if (reset != null) expectedProgress = reset.removed to reset.hearts
        viewModelScope.launch(storeWrites) { dataStore.resetBoard(daily) }
    }

    override fun setGameMode(mode: GameMode) {
        lastBlockedId = -1
        _uiState.value = _uiState.value.copy(moves = emptyList(), showHeartHint = false)
        viewModelScope.launch(storeWrites) { dataStore.setGameMode(mode) }
    }

    override fun dismissRedirectorHint() {
        _uiState.value = _uiState.value.copy(redirectorHintSeen = true)
        viewModelScope.launch(storeWrites) { dataStore.markRedirectorHintSeen() }
    }

    override fun setShowRoutes(enabled: Boolean) {
        viewModelScope.launch(storeWrites) { dataStore.setShowRoutes(enabled) }
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
     * The one place a cleared board is recorded.
     *
     * Reached from the progress collector, so it can fire more than once for the same board as
     * unrelated flows re-emit — and on a fresh ViewModel after process death, when the collector
     * replays onto the still-persisted won board. The banked key in [ArrowsDataStore] makes the
     * count exactly-once across all of those: the check and the bump are one atomic edit, which
     * an in-memory guard could never be.
     */
    private fun onCleared(game: ArrowsGameState, daily: Boolean, day: Long) {
        // Daily clears are keyed by day and casual ones by negated level, which cannot collide.
        val key = if (daily) day else -game.level.toLong()

        viewModelScope.launch(storeWrites) {
            // Null means this board was already banked — a replayed win, not a new clear.
            val total = dataStore.recordClearedOnce(key) ?: return@launch
            val manager = achievementsManager
            manager.onAchievementUnlocked("first_board")
            if (game.hearts == STARTING_HEARTS) manager.onAchievementUnlocked("flawless")
            if (game.puzzle.mirrors.isNotEmpty()) {
                manager.onAchievementUnlocked("through_the_looking_glass")
                // Clearing a board with redirectors shows they are understood; stop explaining.
                dataStore.markRedirectorHintSeen()
            }
            manager.onProgressUpdated("boards_10", total)
            manager.onProgressUpdated("boards_50", total)

            if (daily) {
                manager.onAchievementUnlocked("first_daily")
                val streak = dailyStore.recordDayCompleted(day)
                DailyStreakReporter.report(getApplication(), "arrows", streak, day)
                manager.onProgressUpdated("daily_streak_7", streak.current.toInt())
                manager.onProgressUpdated("daily_streak_30", streak.current.toInt())
            }
        }
    }

    companion object {
        /** Namespace shared by the daily-challenge store and its reminder. */
        const val DAILY_KEY_PREFIX = "arrows_daily"

        private const val REMINDER_NOTIFICATION_ID = 5105

        /**
         * Keeps date-derived daily seeds away from the level-number seeds casual mode uses, and away
         * from the offsets the other games picked (500M pipes, 700M wordmaker, 900M unblockjam,
         * 1.1B nonogram).
         */
        private const val DAILY_SEED_OFFSET = 1_300_000_000L

        /**
         * Keeps retry seeds far from the seeds any first attempt uses, mirroring the generator's
         * own per-round stride so a retried board is genuinely a different search.
         */
        private const val RETRY_SEED_STRIDE = 1_000_003L
    }
}
