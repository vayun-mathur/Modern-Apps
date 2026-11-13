package com.vayunmathur.games.sudoku.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.sudoku.data.ANSWER_MODE_LIVES
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.GameConfig
import com.vayunmathur.games.sudoku.data.SudokuDataStore
import com.vayunmathur.games.sudoku.data.SudokuGameState
import com.vayunmathur.games.sudoku.data.SudokuSnapshot
import com.vayunmathur.games.sudoku.data.SudokuStatsRepository
import com.vayunmathur.games.sudoku.domain.AnswerMode
import com.vayunmathur.games.sudoku.domain.DigitAttempt
import com.vayunmathur.games.sudoku.domain.SudokuGenerator
import com.vayunmathur.library.util.AchievementsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Owns the puzzle in progress.
 *
 * The board is held in memory only. The Activity declares every `configChanges` it can, so this
 * survives rotation and theme changes without a saved-state handle; a puzzle is lost on process
 * death, which matches the other games in this family and is why the home screen offers "Continue"
 * rather than a saved-game list.
 */
class SudokuViewModel(application: Application) : AndroidViewModel(application), SudokuActions {

    private val _uiState = MutableStateFlow(SudokuUiState())
    val uiState: StateFlow<SudokuUiState> = _uiState.asStateFlow()

    private val statsRepository = SudokuStatsRepository(application)
    private val settingsStore = SudokuDataStore(application)

    /**
     * The global default new puzzles are dealt with; the game in progress carries its own copy.
     *
     * Shared while the home screen is up so the switch reflects the stored value. Read once per
     * deal — see [newGame] — rather than observed live, so toggling the switch cannot rewrite a
     * game already in progress.
     */
    val answerMode: StateFlow<Boolean> = settingsStore.answerMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAnswerMode(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAnswerMode(enabled) }
    }

    /**
     * Whether wrong digits draw in red.
     *
     * Read live by the board rather than once per deal like [answerMode], so flipping the switch
     * repaints the game in progress.
     */
    val highlightMistakes: StateFlow<Boolean> = settingsStore.highlightMistakes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setHighlightMistakes(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setHighlightMistakes(enabled) }
    }

    /** Undo snapshots, oldest first. Cleared whenever a new puzzle is dealt. */
    private val history = ArrayDeque<SudokuSnapshot>()

    /** Set when the player ever tries a digit that disagrees with the solution. */
    private var madeMistake = false

    /**
     * Set once the dealt puzzle's win has been written to stats and achievements.
     *
     * [restart] keeps the same givens and solution, so without this a player could
     * re-enter a solution they already know and farm wins, streaks and progress
     * achievements off one puzzle. The win overlay deals a fresh puzzle through
     * [playAgain] instead, so this only guards restart paths. Cleared only by
     * [newGame]: a fresh deal is a fresh record, a restart is not.
     */
    private var winRecorded = false

    /** Sequence numbers for answer-mode rejections, so repeats of one digit still animate. */
    private var rejectionAttempt = 0L

    val achievementsManager: AchievementsManager = run {
        val json = application.assets.open("achievements.json").bufferedReader().readText()
        SudokuAchievementsManager(application, json, statsRepository)
    }

    init {
        achievementsManager.checkExistingAchievements()
    }

    fun hasActiveGame(): Boolean = _uiState.value.game?.isWon == false

    fun getStats(config: GameConfig) = statsRepository.getStats(config.size, config.difficulty)

    fun getSizeStats(size: BoardSize) = statsRepository.getSizeStats(size)

    /**
     * Deals a new puzzle for [config], replacing anything in progress.
     *
     * Generation is dispatched to [Dispatchers.Default] and the board is blanked first, so the
     * screen shows a spinner instead of freezing while a 9x9 Expert is dug.
     */
    fun newGame(config: GameConfig) {
        history.clear()
        madeMistake = false
        winRecorded = false
        rejectionAttempt = 0
        _uiState.value = SudokuUiState(generating = true)
        viewModelScope.launch {
            val puzzle = withContext(Dispatchers.Default) {
                SudokuGenerator.generate(config.size, config.difficulty, Random.Default)
            }
            statsRepository.recordGamePlayed(config.size, config.difficulty)
            _uiState.value = SudokuUiState(game = SudokuGameState.from(puzzle, config.answerMode))
        }
    }

    /**
     * Moves the cursor to [index].
     *
     * Re-tapping the selected cell keeps it selected. It used to toggle the selection off, so a
     * player double-checking a cell (or a slightly bouncy tap) lost their place and the next
     * number-pad press silently did nothing.
     */
    override fun selectCell(index: Int) {
        mutate { it.copy(selected = index) }
    }

    override fun toggleNotesMode() {
        mutate { it.copy(notesMode = !it.notesMode) }
    }

    /**
     * Writes [digit] into the selected cell, as a pencil mark when notes mode is on.
     *
     * Re-entering the digit already showing clears the cell instead, so the number pad doubles as a
     * toggle and the player rarely needs the separate erase button.
     *
     * What the tap *means* is decided by [AnswerMode.decide] — this only carries it out. In
     * particular a digit that disagrees with the solution goes in silently in classic mode (the
     * player finds out only because the win never fires), while in answer mode it is refused: never
     * placed, one life spent, the key flashes. The refusal is remembered for the `no_mistakes`
     * achievement exactly like a classic-mode wrong entry.
     */
    override fun enterDigit(digit: Int) {
        val game = _uiState.value.game ?: return
        when (val attempt = AnswerMode.decide(game, digit)) {
            is DigitAttempt.Ignore -> return
            is DigitAttempt.Reject -> rejectDigit(attempt.digit)
            is DigitAttempt.Note -> enterNote(game, attempt.index, attempt.digit)
            is DigitAttempt.Erase -> eraseEntry(game, attempt.index)
            is DigitAttempt.Place -> placeDigit(game, attempt.index, attempt.digit)
        }
    }

    /**
     * Clears one entered digit; re-tapping a digit is how the pad doubles as an eraser.
     *
     * Pencil marks on the cell go too: they were candidates for a digit the player just took back.
     */
    private fun eraseEntry(game: SudokuGameState, index: Int) {
        pushHistory(game)
        mutate { state ->
            state.copy(
                entries = state.entries.replacing(index, 0),
                notes = state.notes.replacing(index, 0),
                moveCount = state.moveCount + 1,
            )
        }
    }

    /**
     * Writes one digit onto the board.
     *
     * In answer mode this is only ever reached with the correct digit — wrong ones are rejected
     * before they get here — so the mistake accounting below only ever fires for classic games,
     * exactly as it always has.
     */
    private fun placeDigit(game: SudokuGameState, index: Int, digit: Int) {
        pushHistory(game)
        if (digit != game.solution[index]) madeMistake = true
        mutate { state ->
            state.copy(
                entries = state.entries.replacing(index, digit),
                // A written digit makes its own pencil marks meaningless, and it is no
                // longer a candidate anywhere it can be seen from.
                notes = state.notesAfterPlacement(index, digit),
                moveCount = state.moveCount + 1,
            )
        }
        checkWin()
    }

    /**
     * Toggles one pencil mark, unconditionally.
     *
     * Extracted from [enterDigit] so the answer-mode guard sits in one place: notes are written
     * digits' rough drafts, and refusing to pencil in a wrong digit would make the mode unusable
     * for the elimination it is meant to encourage.
     */
    private fun enterNote(game: SudokuGameState, index: Int, digit: Int) {
        pushHistory(game)
        val bit = 1 shl (digit - 1)
        mutate { state ->
            state.copy(
                notes = state.notes.replacing(index, state.notes[index] xor bit),
                entries = state.entries.replacing(index, 0),
                moveCount = state.moveCount + 1,
            )
        }
    }

    /**
     * Refuses [digit]: no placement, one life spent, and the key flashes.
     *
     * The life is spent even on a game this ends — the board that hits zero shows the loss as the
     * cost of the tap that caused it, rather than freezing first. History is untouched, so there
     * is nothing to undo: nothing changed on the board. [madeMistake] is set too, keeping answer
     * games eligible for the same `no_mistakes` accounting as classic ones.
     */
    private fun rejectDigit(digit: Int) {
        madeMistake = true
        rejectionAttempt++
        mutate { state -> AnswerMode.applyRejection(state) }
        _uiState.update { it.copy(rejectedDigit = RejectedDigit(digit, rejectionAttempt)) }
        val game = _uiState.value.game ?: return
        if (game.isGameOver) statsRepository.recordGameLost(game.size, game.difficulty)
    }

    override fun clearCell() {
        val game = _uiState.value.game ?: return
        val index = game.selected
        if (index !in 0 until game.size.cellCount || game.isGiven(index)) return
        if (game.isWon || game.isGameOver) return
        // Nothing to clear: pushing history here would leave an undo step that visibly
        // does nothing and count a move the player never made.
        if (game.entries[index] == 0 && game.notes[index] == 0) return
        pushHistory(game)
        mutate { state ->
            state.copy(
                entries = state.entries.replacing(index, 0),
                notes = state.notes.replacing(index, 0),
                moveCount = state.moveCount + 1,
            )
        }
    }

    /**
     * Fixes one cell, correcting a mistake before filling anything new.
     *
     * Errors come first wherever they are on the board: a hint spent on a fresh cell while a wrong
     * digit sits elsewhere would leave the player deducing from something false. Only once the grid is
     * consistent does it fill a blank, preferring the selected one so help lands where they are
     * looking.
     *
     * A hint always places a correct digit, so it costs no life in answer mode — and in answer mode
     * there are never wrong digits to correct first, because wrong digits are never placed.
     */
    override fun hint() {
        val game = _uiState.value.game ?: return
        if (game.isWon || game.isGameOver) return
        val index = game.wrongIndices().firstOrNull()
            ?: game.selected.takeIf { it >= 0 && !game.isGiven(it) && game.valueAt(it) == 0 }
            ?: game.blankIndices().firstOrNull()
            ?: return

        pushHistory(game)
        mutate { state ->
            state.copy(
                entries = state.entries.replacing(index, state.solution[index]),
                notes = state.notesAfterPlacement(index, state.solution[index]),
                selected = index,
                hintsUsed = state.hintsUsed + 1,
                moveCount = state.moveCount + 1,
            )
        }
        checkWin()
    }

    /**
     * Steps the board back one move.
     *
     * A no-op once the game has ended. Undoing on a won board used to restore the pre-win entries
     * while `isWon` stayed true, reopening a "solved" grid the player could then edit; and on an
     * answer-mode loss it let the player keep working a board the overlay says is over.
     */
    override fun undo() {
        val game = _uiState.value.game ?: return
        if (game.isWon || game.isGameOver) return
        val snapshot = history.removeLastOrNull() ?: return
        mutate { it.copy(entries = snapshot.entries, notes = snapshot.notes, usedUndo = true) }
        _uiState.update { it.copy(canUndo = history.isNotEmpty()) }
    }

    /** Clears the player's work but keeps the same puzzle — and, in answer mode, refills lives. */
    override fun restart() {
        val game = _uiState.value.game ?: return
        history.clear()
        madeMistake = false
        rejectionAttempt = 0
        _uiState.value = SudokuUiState(
            game = game.copy(
                entries = List(game.size.cellCount) { 0 },
                notes = List(game.size.cellCount) { 0 },
                selected = -1,
                moveCount = 0,
                elapsedSeconds = 0,
                hintsUsed = 0,
                isWon = false,
                usedUndo = false,
                livesRemaining = ANSWER_MODE_LIVES,
            )
        )
    }

    /**
     * Deals a new puzzle with the settings the current one was dealt with.
     *
     * This is the win overlay's "Play again". It used to call [restart], which re-dealt the same
     * grid; because a puzzle's win is recorded once (see [winRecorded]) the replay could never
     * count, so a player chaining games after a win silently stopped earning wins and streaks.
     * The game's own answer-mode snapshot is reused rather than the home-screen default, so the
     * next puzzle follows the same rules as the one just finished.
     */
    override fun playAgain() {
        val game = _uiState.value.game ?: return
        newGame(GameConfig(game.size, game.difficulty, game.answerMode))
    }

    /** Abandons an unfinished puzzle, which breaks the win streak for that variant. */
    override fun giveUp() {
        val game = _uiState.value.game ?: return
        if (!game.isWon) statsRepository.recordGameLost(game.size, game.difficulty)
        history.clear()
        _uiState.value = SudokuUiState()
    }

    fun incrementTimer() {
        val game = _uiState.value.game ?: return
        if (game.isWon || game.isGameOver) return
        mutate { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }
    }

    fun dismissAchievementNotification() = achievementsManager.dismissNotification()

    /**
     * The one place a win is recorded, so stats and achievements can never disagree about what
     * happened. Every path that can complete the grid ends up here.
     */
    private fun checkWin() {
        val game = _uiState.value.game ?: return
        if (game.isWon || !game.isComplete) return
        mutate { it.copy(isWon = true, selected = -1) }

        // The board still shows its win on a restart replay; only the recording is once-only.
        if (winRecorded) return
        winRecorded = true
        statsRepository.recordGameWon(game.size, game.difficulty, game.elapsedSeconds)
        val manager = achievementsManager
        manager.onAchievementUnlocked("first_win")
        if (game.size == BoardSize.NINE) {
            manager.onAchievementUnlocked("win_nine")
            if (game.hintsUsed == 0) manager.onAchievementUnlocked("no_hints")
            if (game.elapsedSeconds < SudokuAchievementsManager.SPEED_SECONDS) {
                manager.onAchievementUnlocked("speed_nine")
            }
        }
        if (game.difficulty == Difficulty.EXPERT) {
            manager.onAchievementUnlocked("win_expert")
        }
        if (!madeMistake) manager.onAchievementUnlocked("no_mistakes")
        if (BoardSize.entries.all { statsRepository.getSizeStats(it).gamesWon > 0 }) {
            manager.onAchievementUnlocked("every_size")
        }
        val totalWins = statsRepository.getTotalGamesWon()
        manager.onProgressUpdated("wins_10", totalWins)
        manager.onProgressUpdated("wins_50", totalWins)
        manager.onProgressUpdated("win_streak_5", statsRepository.getBestWinStreak())
    }

    /**
     * The note masks after [digit] is written at [index]: the cell's own marks go, and the
     * digit stops being a candidate in every cell that shares its row, column or box.
     *
     * Without the peer sweep a placed digit stayed pencilled into its neighbours, so the
     * board kept claiming a digit was possible where it had just been ruled out — and the
     * player had to erase those marks by hand after every placement.
     */
    private fun SudokuGameState.notesAfterPlacement(index: Int, digit: Int): List<Int> {
        val clear = (1 shl (digit - 1)).inv()
        val row = index / size.side
        val col = index % size.side
        val box = size.boxOf(index)
        return notes.mapIndexed { other, mask ->
            when {
                other == index -> 0
                other / size.side == row || other % size.side == col ||
                    size.boxOf(other) == box -> mask and clear
                else -> mask
            }
        }
    }

    private fun pushHistory(game: SudokuGameState) {
        history.addLast(SudokuSnapshot(game.entries, game.notes))
        if (history.size > MAX_HISTORY) history.removeFirst()
        _uiState.update { it.copy(canUndo = true) }
    }

    private inline fun mutate(transform: (SudokuGameState) -> SudokuGameState) {
        _uiState.update { state ->
            val game = state.game ?: return@update state
            state.copy(game = transform(game))
        }
    }

    private companion object {
        /**
         * Undo depth. A snapshot is two lists of at most 81 ints, so this is a few hundred KB at
         * worst — bounded mainly so a long session cannot grow without limit.
         */
        const val MAX_HISTORY = 200
    }
}

/** [this] with [index] replaced by [value]. */
private fun List<Int>.replacing(index: Int, value: Int): List<Int> =
    toMutableList().also { it[index] = value }
