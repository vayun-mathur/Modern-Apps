package com.vayunmathur.games.sudoku.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class SudokuProgressCodecTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val config = GameConfig(BoardSize.SIX, Difficulty.EASY, answerMode = false)

    private fun midGame(): SavedSudokuGame {
        val size = BoardSize.SIX
        val givens = List(size.cellCount) { if (it % 3 == 0) (it % size.side) + 1 else 0 }
        val solution = List(size.cellCount) { (it % size.side) + 1 }
        val game = SudokuGameState(
            size = size,
            difficulty = Difficulty.EASY,
            givens = givens,
            solution = solution,
            entries = List(size.cellCount) { if (it == 1) 4 else 0 },
            notes = List(size.cellCount) { if (it == 2) 0b101 else 0 },
            selected = 1,
            notesMode = true,
            moveCount = 3,
            hintsUsed = 1,
            usedUndo = true,
            answerMode = false,
            livesRemaining = 2,
        )
        val history = listOf(
            SudokuSnapshot(List(size.cellCount) { 0 }, List(size.cellCount) { 0 }),
        )
        return SavedSudokuGame(game, history, elapsedSeconds = 87)
    }

    @Test
    fun midGameRoundTripsThroughCodec() {
        val saved = midGame()
        assertEquals(saved, SudokuProgressCodec.decode(SudokuProgressCodec.encode(saved), config))
    }

    @Test
    fun mismatchedConfigAndCorruptJsonAreDropped() {
        val encoded = SudokuProgressCodec.encode(midGame())
        assertNull(SudokuProgressCodec.decode(encoded, GameConfig(BoardSize.NINE, Difficulty.EASY)))
        assertNull(SudokuProgressCodec.decode(encoded, GameConfig(BoardSize.SIX, Difficulty.HARD)))
        assertNull(
            SudokuProgressCodec.decode(
                encoded,
                GameConfig(BoardSize.SIX, Difficulty.EASY, answerMode = true),
            ),
        )
        assertNull(SudokuProgressCodec.decode("not json", config))
        assertNull(SudokuProgressCodec.decode("""{"v":2}""", config))
    }

    @Test
    fun midGameRoundTripsThroughDataStore() = runBlocking {
        val store = SudokuDataStore(context)
        store.clearInProgress(config.variant)
        val saved = midGame()
        store.saveInProgress(saved)
        assertEquals(saved, assertNotNull(SudokuDataStore(context).loadInProgress(config)))
        // A different variant has no saved game.
        assertNull(store.loadInProgress(GameConfig(BoardSize.SIX, Difficulty.HARD)))
        store.clearInProgress(config.variant)
        assertNull(store.loadInProgress(config))
    }
}
