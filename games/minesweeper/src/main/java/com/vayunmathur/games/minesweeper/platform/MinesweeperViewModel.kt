package com.vayunmathur.games.minesweeper.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState
import com.vayunmathur.games.minesweeper.data.MinesweeperStatsRepository
import com.vayunmathur.games.minesweeper.data.TapMode
import com.vayunmathur.games.minesweeper.domain.FieldGenerator
import com.vayunmathur.games.minesweeper.domain.MinesweeperRules
import com.vayunmathur.library.util.AchievementsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/**
 * Owns the field in progress.
 *
 * The field is held in memory only. The Activity declares every `configChanges` it can, so this
 * survives rotation and theme changes without a saved-state handle; a game is lost on process death,
 * which matches the other games in this family and is why the home screen offers "Continue" rather
 * than a saved-game list.
 *
 * Unlike sudoku there is no generation cost worth dispatching off the main thread: laying mines is a
 * shuffle and one pass for the neighbour counts, so it runs inline on the first tap.
 */
class MinesweeperViewModel(application: Application) :
    AndroidViewModel(application), MinesweeperActions {

    private val _uiState = MutableStateFlow(MinesweeperUiState())
    val uiState: StateFlow<MinesweeperUiState> = _uiState.asStateFlow()

    private val statsRepository = MinesweeperStatsRepository(application)

    private val _elapsedSeconds = MutableStateFlow(0)

    /**
     * Seconds on the clock for the field in progress.
     *
     * Its own flow rather than a field of [MinesweeperGameState]: the game state feeds every cell,
     * so a 1 Hz tick inside it redrew the whole field every second. Only the clock text reads this.
     */
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    /**
     * Whether the player has placed a flag this game.
     *
     * Cannot be read off the finished state: winning marks every remaining mine, so a cleared board
     * always looks fully flagged regardless of what the player actually did.
     */
    private var placedAnyFlag = false

    val achievementsManager: AchievementsManager = run {
        val json = application.assets.open("achievements.json").bufferedReader().readText()
        MinesweeperAchievementsManager(application, json, statsRepository)
    }

    init {
        achievementsManager.checkExistingAchievements()
    }

    fun hasActiveGame(): Boolean = _uiState.value.game?.isOver == false

    fun getSizeStats(size: BoardSize) = statsRepository.getSizeStats(size)

    /**
     * Deals a blank field for [config]. Mines are not laid until the first dig.
     *
     * Every field starts in dig mode. Flag mode used to carry over from the previous field, so a
     * player who finished in flag mode met a fresh field where the first tap did nothing at all —
     * there is nothing to flag before the mines are laid — while the hint said "Tap to open".
     */
    fun newGame(config: GameConfig) {
        placedAnyFlag = false
        _elapsedSeconds.value = 0
        _uiState.value = MinesweeperUiState(
            config = config,
            game = MinesweeperGameState.empty(config),
            tapMode = TapMode.DIG,
        )
    }

    /**
     * Tap: digs, flags, or chords, depending on the mode and what is under the finger.
     *
     * In flag mode a tap can never uncover anything, which is the whole point — clearing a run of
     * suspected mines should not risk ending the game on a mis-tap. That includes chording: a chord
     * opens cells (and trusts flags that may be wrong), so a flag-mode tap on an open number used to
     * uncover a mine and end the game in the one mode promising it cannot. In flag mode the chord
     * moves to the long press, the mode's dig gesture.
     *
     * The opening tap always digs, whatever the mode: before it no mines exist, so a flag-mode tap
     * could only be a silent no-op, and the opening is guaranteed safe anyway.
     */
    override fun tapCell(index: Int) {
        val state = _uiState.value
        val game = state.game ?: return
        if (game.isOver) return
        if (!game.started) {
            dig(state, game, index)
            return
        }

        if (game.revealed[index]) {
            if (state.tapMode == TapMode.DIG) chord(game, index)
            return
        }
        if (state.tapMode == TapMode.FLAG) {
            toggleFlag(game, index)
            return
        }
        dig(state, game, index)
    }

    /**
     * Long press: the other action from whatever the mode is.
     *
     * In flag mode that is digging, so a long press on an open number chords — the opening gesture
     * stays available in flag mode, just never on the plain tap.
     */
    override fun flagCell(index: Int): Boolean {
        val state = _uiState.value
        val game = state.game ?: return false
        if (game.isOver) return false
        return when {
            state.tapMode == TapMode.DIG -> toggleFlag(game, index)
            game.revealed[index] -> { chord(game, index); false }
            else -> { dig(state, game, index); false }
        }
    }

    override fun setTapMode(mode: TapMode) {
        _uiState.update { it.copy(tapMode = mode) }
    }

    /**
     * Uncovers [index], laying the mines first if this is the opening move.
     *
     * The first dig is also where the game counts as played — backing out of a field you never touched
     * should not show up in the stats.
     */
    private fun dig(
        state: MinesweeperUiState,
        game: MinesweeperGameState,
        index: Int,
    ) {
        val laid = if (game.started) game else {
            statsRepository.recordGamePlayed(state.config.size, state.config.difficulty)
            FieldGenerator.lay(game, index, Random.Default)
        }
        publish(MinesweeperRules.reveal(laid, index))
    }

    private fun chord(game: MinesweeperGameState, index: Int) {
        val chorded = MinesweeperRules.chord(game, index)
        // Only counts when the chord was legal and actually opened something.
        if (chorded !== game) achievementsManager.onAchievementUnlocked("first_chord")
        publish(chorded)
    }

    /** Returns whether the flag actually changed; open cells and unstarted fields refuse. */
    private fun toggleFlag(game: MinesweeperGameState, index: Int): Boolean {
        // Flagging before the first dig would have nothing to flag: the mines are not laid yet.
        if (!game.started) return false
        val next = MinesweeperRules.toggleFlag(game, index)
        if (next === game) return false
        if (next.flagsPlaced > game.flagsPlaced) placedAnyFlag = true
        publish(next)
        return true
    }

    /** A fresh field with the same settings. */
    override fun restart() {
        newGame(_uiState.value.config)
    }

    /** Abandons an unfinished field, which breaks the win streak for that variant. */
    override fun giveUp() {
        val state = _uiState.value
        val game = state.game ?: return
        if (game.started && !game.isOver) {
            statsRepository.recordGameLost(state.config.size, state.config.difficulty)
        }
        _elapsedSeconds.value = 0
        _uiState.value = MinesweeperUiState(config = state.config, tapMode = state.tapMode)
    }

    fun incrementTimer() {
        val game = _uiState.value.game ?: return
        if (!game.started || game.isOver) return
        _elapsedSeconds.update { it + 1 }
    }

    fun dismissAchievementNotification() = achievementsManager.dismissNotification()

    /**
     * Stores [next] and, when it has just ended, records the result.
     *
     * Every path that can finish a game goes through here, so stats and achievements can never
     * disagree about what happened.
     */
    private fun publish(next: MinesweeperGameState) {
        val previous = _uiState.value.game
        _uiState.update { it.copy(game = next) }

        val justEnded = previous != null && !previous.isOver && next.isOver
        if (!justEnded) return

        val config = _uiState.value.config
        when (next.outcome) {
            GameOutcome.WON -> onWin()
            GameOutcome.LOST -> statsRepository.recordGameLost(config.size, config.difficulty)
            GameOutcome.PLAYING -> Unit
        }
    }

    private fun onWin() {
        val config = _uiState.value.config
        val elapsed = _elapsedSeconds.value
        statsRepository.recordGameWon(config.size, config.difficulty, elapsed)

        val manager = achievementsManager
        manager.onAchievementUnlocked("first_win")
        if (config.size == BoardSize.LARGE) manager.onAchievementUnlocked("win_large")
        if (config.difficulty == Difficulty.EXPERT) manager.onAchievementUnlocked("win_expert")
        if (!placedAnyFlag) manager.onAchievementUnlocked("no_flags_needed")
        if (elapsed < FAST_WIN_SECONDS) manager.onAchievementUnlocked("speed_win")

        val totalWins = statsRepository.getTotalGamesWon()
        manager.onProgressUpdated("wins_10", totalWins)
        manager.onProgressUpdated("wins_50", totalWins)
        manager.onProgressUpdated("win_streak_5", statsRepository.getBestWinStreak())
    }

    private companion object {
        /** Matches the "under a minute" wording of the `speed_win` description. */
        const val FAST_WIN_SECONDS = 60
    }
}
