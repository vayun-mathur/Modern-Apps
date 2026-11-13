package com.vayunmathur.games.minesweeper.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState
import com.vayunmathur.games.minesweeper.data.TapMode
import com.vayunmathur.games.minesweeper.data.neighbourIndices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Model maths (mine counts, neighbours) and the ViewModel game lifecycle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MinesweeperViewModelTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = MinesweeperViewModel(app)

    @Test
    fun mineCountsScaleAndCap() {
        assertEquals(80, BoardSize.SMALL.cellCount)
        assertEquals(140, BoardSize.MEDIUM.cellCount)
        assertEquals(216, BoardSize.LARGE.cellCount)
        assertEquals(8, Difficulty.EASY.mineCount(BoardSize.SMALL))
        assertEquals(21, Difficulty.MEDIUM.mineCount(BoardSize.MEDIUM))
        // Cap: never so many mines that the 9-cell opening has nowhere safe.
        assertTrue(Difficulty.EXPERT.mineCount(BoardSize.SMALL) <= BoardSize.SMALL.cellCount - 9)
        assertEquals("MEDIUM_MEDIUM", GameConfig().variant)
        assertEquals("SMALL_EASY", GameConfig(BoardSize.SMALL, Difficulty.EASY).variant)
    }

    @Test
    fun neighbourIndicesCornersEdgesCenter() {
        // 3x3 field, row-major 0..8.
        assertEquals(listOf(1, 3, 4), neighbourIndices(3, 3, 0).sorted())
        assertEquals(listOf(0, 2, 3, 4, 5), neighbourIndices(3, 3, 1).sorted())
        assertEquals(8, neighbourIndices(3, 3, 4).size)
        assertEquals(listOf(4, 5, 7), neighbourIndices(3, 3, 8).sorted())
        val state = MinesweeperGameState.of(3, 3, 1)
        assertEquals(neighbourIndices(3, 3, 4), state.neighbours(4))
    }

    @Test
    fun emptyStateDerivedCounts() {
        val s = MinesweeperGameState.empty(GameConfig(BoardSize.SMALL, Difficulty.EASY))
        assertEquals(8, s.cols)
        assertEquals(10, s.rows)
        assertEquals(8, s.mineCount)
        assertFalse(s.started)
        assertEquals(8, s.minesRemaining)
        assertFalse(s.isOver)
        assertFalse(s.isCleared)
        val flagged = s.copy(flagged = List(s.cellCount) { it < 3 })
        assertEquals(3, flagged.flagsPlaced)
        assertEquals(5, flagged.minesRemaining)
        // Over-flagging drives the counter negative.
        val over = s.copy(flagged = List(s.cellCount) { it < 10 })
        assertEquals(-2, over.minesRemaining)
    }

    @Test
    fun newGameDealsBlankFieldInDigMode() {
        val vm = vm()
        vm.setTapMode(TapMode.FLAG)
        vm.newGame(GameConfig(BoardSize.SMALL, Difficulty.EASY))
        val state = vm.uiState.value
        assertEquals(TapMode.DIG, state.tapMode, "a fresh field always starts in dig mode")
        val game = state.game!!
        assertEquals(80, game.cellCount)
        assertTrue(game.mines.none { it })
        assertFalse(game.started)
        assertTrue(vm.hasActiveGame())
    }

    @Test
    fun firstTapLaysMinesSafelyAndStartsClock() {
        val vm = vm()
        vm.newGame(GameConfig(BoardSize.SMALL, Difficulty.EASY))
        vm.tapCell(0)
        val game = vm.uiState.value.game!!
        assertTrue(game.started)
        assertEquals(8, game.mines.count { it })
        // Opening cell and its neighbours are mine-free and revealed by the cascade.
        assertFalse(game.mines[0])
        assertTrue(game.neighbours(0).none { game.mines[it] })
        assertTrue(game.revealed[0])
        assertTrue(game.revealed.count { it } > 1)
        assertEquals(GameOutcome.PLAYING, game.outcome)
        val beforeTick = vm.uiState.value.game!!
        vm.incrementTimer()
        assertEquals(1, vm.elapsedSeconds.value)
        assertSame(beforeTick, vm.uiState.value.game!!, "a tick must not hand the field a new state")
        vm.restart()
        assertEquals(0, vm.elapsedSeconds.value, "a new field starts the clock from zero")
    }

    @Test
    fun firstTapDigsEvenInFlagModeAndRestartResetsToDig() {
        val vm = vm()
        vm.newGame(GameConfig(BoardSize.SMALL, Difficulty.EASY))
        vm.setTapMode(TapMode.FLAG)
        vm.tapCell(0)
        val game = vm.uiState.value.game!!
        assertTrue(game.started, "the opening tap digs whatever the mode")
        assertTrue(game.revealed[0])
        assertEquals(0, game.flagsPlaced)
        assertEquals(TapMode.FLAG, vm.uiState.value.tapMode, "the chosen mode stays for this field")

        vm.restart()
        assertEquals(TapMode.DIG, vm.uiState.value.tapMode)
    }

    @Test
    fun flagBeforeStartDoesNothingModeToggleFlagsAfterStart() {
        val vm = vm()
        vm.newGame(GameConfig(BoardSize.SMALL, Difficulty.EASY))
        assertFalse(vm.flagCell(5), "no flag before the mines are laid")
        assertEquals(0, vm.uiState.value.game!!.flagsPlaced)
        vm.tapCell(0)
        val started = vm.uiState.value.game!!
        val hidden = started.revealed.indices.first { !started.revealed[it] && !started.mines[it] }
        assertTrue(vm.flagCell(hidden), "placing a flag reports a change")
        assertEquals(1, vm.uiState.value.game!!.flagsPlaced)
        assertTrue(vm.flagCell(hidden), "removing it does too")
        assertEquals(0, vm.uiState.value.game!!.flagsPlaced)
        assertFalse(vm.flagCell(0), "an open cell cannot be flagged")
        // Flag-mode tap also flags (never digs).
        vm.setTapMode(TapMode.FLAG)
        vm.tapCell(hidden)
        assertEquals(1, vm.uiState.value.game!!.flagsPlaced)
    }

    /**
     * A started field with an open number whose mines are all flagged and which still has a covered
     * safe neighbour, i.e. a legal chord that would open something.
     */
    private fun chordReady(vm: MinesweeperViewModel): Int {
        repeat(200) {
            vm.newGame(GameConfig(BoardSize.MEDIUM, Difficulty.MEDIUM))
            vm.tapCell(70)
            val game = vm.uiState.value.game!!
            if (game.isOver) return@repeat
            val number = game.revealed.indices.firstOrNull { i ->
                game.revealed[i] && game.neighbourCounts[i] > 0 &&
                    game.neighbours(i).any { !game.revealed[it] && !game.mines[it] }
            } ?: return@repeat
            game.neighbours(number).filter { game.mines[it] }.forEach { vm.flagCell(it) }
            return number
        }
        error("no chordable field in 200 deals")
    }

    @Test
    fun flagModeTapNeverChordsButLongPressDoes() {
        val vm = vm()
        val number = chordReady(vm)
        vm.setTapMode(TapMode.FLAG)
        val before = vm.uiState.value.game!!
        vm.tapCell(number)
        assertEquals(before.revealed, vm.uiState.value.game!!.revealed, "a flag-mode tap opens nothing")
        assertEquals(before.flagged, vm.uiState.value.game!!.flagged, "and flags nothing on an open cell")

        vm.flagCell(number)
        val after = vm.uiState.value.game!!
        assertTrue(after.revealed.count { it } > before.revealed.count { it }, "long press chords")
    }

    @Test
    fun digModeTapStillChords() {
        val vm = vm()
        val number = chordReady(vm)
        val before = vm.uiState.value.game!!
        vm.tapCell(number)
        assertTrue(vm.uiState.value.game!!.revealed.count { it } > before.revealed.count { it })
    }

    @Test
    fun giveUpClearsGameTimerStopsWhenOverOrUnstarted() {
        val vm = vm()
        vm.newGame(GameConfig())
        vm.incrementTimer()
        assertEquals(0, vm.elapsedSeconds.value)
        vm.tapCell(40)
        vm.giveUp()
        assertNull(vm.uiState.value.game)
        assertFalse(vm.hasActiveGame())
        // Restart deals a fresh blank field with the same config.
        vm.newGame(GameConfig(BoardSize.LARGE, Difficulty.HARD))
        vm.restart()
        val game = vm.uiState.value.game!!
        assertEquals(216, game.cellCount)
        assertFalse(game.started)
    }
}
