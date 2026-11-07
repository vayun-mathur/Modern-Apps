package com.vayunmathur.games.minesweeper.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.MinesweeperStatsRepository
import com.vayunmathur.games.minesweeper.data.TapMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Win/lose publishing, chording, mode interplay and give-up accounting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MinesweeperViewModelFlowsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = MinesweeperViewModel(app)
    private fun stats() = MinesweeperStatsRepository(app)

    @BeforeTest fun clearPrefs() {
        app.getSharedPreferences("minesweeper_stats", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun startedVm(): MinesweeperViewModel {
        val vm = vm()
        vm.newGame(GameConfig(BoardSize.SMALL, Difficulty.EASY))
        vm.tapCell(0)
        assertTrue(vm.uiState.value.game!!.started)
        return vm
    }

    @Test fun winningByRevealingEverySafeCellRecordsWin() {
        val vm = startedVm()
        // Dig every remaining safe cell; cascades do most of the work.
        while (true) {
            val game = vm.uiState.value.game!!
            if (game.isOver) break
            val next = game.revealed.indices.firstOrNull { !game.revealed[it] && !game.mines[it] } ?: break
            vm.tapCell(next)
        }
        val game = vm.uiState.value.game!!
        assertEquals(GameOutcome.WON, game.outcome)
        assertFalse(vm.hasActiveGame())
        val sizeStats = vm.getSizeStats(BoardSize.SMALL)
        assertEquals(1, sizeStats.gamesWon)
        assertEquals(1, sizeStats.gamesPlayed)
        // Taps and timer after the end are ignored.
        val mines = game.mines.indices.first { game.mines[it] }
        vm.tapCell(mines)
        vm.flagCell(mines)
        vm.incrementTimer()
        assertEquals(game, vm.uiState.value.game)
        vm.dismissAchievementNotification()
    }

    @Test fun losingOnAMineRecordsLossAndExplodedCell() {
        // Seed a streak so the loss visibly resets it.
        stats().recordGameWon(BoardSize.SMALL, Difficulty.EASY, 120)
        val vm = startedVm()
        val game = vm.uiState.value.game!!
        val mine = game.mines.indices.first { game.mines[it] && !game.revealed[it] }
        vm.tapCell(mine)
        val lost = vm.uiState.value.game!!
        assertEquals(GameOutcome.LOST, lost.outcome)
        assertEquals(mine, lost.explodedAt)
        assertFalse(vm.hasActiveGame())
        // The pre-seeded win streak is broken by the recorded loss.
        assertEquals(0, stats().getStats(BoardSize.SMALL, Difficulty.EASY).currentWinStreak)
        assertEquals(1, stats().getStats(BoardSize.SMALL, Difficulty.EASY).gamesWon)
    }

    /** Deals fresh fields until the opening cascade leaves a usable chord target. */
    private fun chordTarget(vm: MinesweeperViewModel, minCount: Int): Int {
        repeat(25) {
            vm.newGame(GameConfig(BoardSize.SMALL, Difficulty.EASY))
            vm.tapCell(0)
            val game = vm.uiState.value.game!!
            val idx = game.revealed.indices.firstOrNull { i ->
                game.revealed[i] && game.neighbourCounts[i] >= minCount &&
                    game.neighbours(i).any { !game.revealed[it] && !game.mines[it] } &&
                    game.neighbours(i).count { game.mines[it] } == game.neighbourCounts[i]
            }
            if (idx != null) return idx
        }
        throw AssertionError("no chord target found in 25 deals")
    }

    @Test fun chordingOpensNeighboursWhenFlagsMatch() {
        val vm = vm()
        val idx = chordTarget(vm, minCount = 1)
        val game = vm.uiState.value.game!!
        val mineNeighbours = game.neighbours(idx).filter { game.mines[it] }
        for (m in mineNeighbours) vm.flagCell(m)
        val before = vm.uiState.value.game!!.revealed.count { it }
        vm.tapCell(idx) // chord
        val after = vm.uiState.value.game!!
        assertTrue(after.revealed.count { it } > before, "chord should open the safe neighbours")
        assertFalse(after.isOver && after.outcome == GameOutcome.LOST)
    }

    @Test fun chordingWithWrongFlagCountDoesNothing() {
        val vm = vm()
        val idx = chordTarget(vm, minCount = 2)
        val game = vm.uiState.value.game!!
        // One correct flag is still fewer than the count: chord must not fire.
        vm.flagCell(game.neighbours(idx).first { game.mines[it] })
        val before = vm.uiState.value.game!!
        vm.tapCell(idx)
        assertEquals(before, vm.uiState.value.game)
    }

    @Test fun flagModeLongPressDigsAndDigModeLongPressFlags() {
        val vm = vm()
        vm.newGame(GameConfig(BoardSize.SMALL, Difficulty.EASY))
        vm.setTapMode(TapMode.FLAG)
        assertEquals(TapMode.FLAG, vm.uiState.value.tapMode)
        // Long press in flag mode is the dig: it lays mines and starts.
        vm.flagCell(40)
        var game = vm.uiState.value.game!!
        assertTrue(game.started)
        assertTrue(game.revealed[40])
        // Back in dig mode, long press flags a hidden cell.
        vm.setTapMode(TapMode.DIG)
        val hidden = game.revealed.indices.first { !game.revealed[it] }
        vm.flagCell(hidden)
        game = vm.uiState.value.game!!
        assertTrue(game.flagged[hidden])
        // Flagging a revealed cell does nothing.
        vm.flagCell(40)
        assertFalse(vm.uiState.value.game!!.flagged[40])
    }

    @Test fun giveUpBeforeStartRecordsNoLossButClearsField() {
        stats().recordGameWon(BoardSize.MEDIUM, Difficulty.MEDIUM, 100)
        val vm = vm()
        vm.newGame(GameConfig()) // MEDIUM/MEDIUM, not started
        vm.giveUp()
        assertNull(vm.uiState.value.game)
        // Streak untouched: the field was never played.
        assertEquals(1, stats().getStats(BoardSize.MEDIUM, Difficulty.MEDIUM).currentWinStreak)
        // Give up with no game at all is also a quiet no-op, keeping config/mode.
        vm.setTapMode(TapMode.FLAG)
        vm.giveUp()
        assertEquals(GameConfig(), vm.uiState.value.config)
        assertEquals(TapMode.FLAG, vm.uiState.value.tapMode)
    }

    @Test fun restartKeepsConfigAndTapModeTimerNeedsStartedGame() {
        val vm = vm()
        vm.newGame(GameConfig(BoardSize.LARGE, Difficulty.EXPERT))
        vm.setTapMode(TapMode.FLAG)
        vm.restart()
        var state = vm.uiState.value
        assertEquals(GameConfig(BoardSize.LARGE, Difficulty.EXPERT), state.config)
        assertEquals(TapMode.FLAG, state.tapMode)
        assertEquals(216, state.game!!.cellCount)
        // Timer does not run before the first dig.
        vm.incrementTimer()
        assertEquals(0, vm.uiState.value.game!!.elapsedSeconds)
        // No game: timer and taps are no-ops.
        vm.giveUp()
        assertNull(vm.uiState.value.game)
        vm.incrementTimer()
        vm.tapCell(0)
        vm.flagCell(0)
        assertNull(vm.uiState.value.game)
        assertFalse(vm.hasActiveGame())
    }

    @Test fun actionsNoopDefaults() {
        val noop = MinesweeperActions.Noop
        noop.tapCell(0); noop.flagCell(0); noop.setTapMode(TapMode.FLAG); noop.restart(); noop.giveUp()
        assertEquals(TapMode.DIG, vm().uiState.value.tapMode)
    }
}
