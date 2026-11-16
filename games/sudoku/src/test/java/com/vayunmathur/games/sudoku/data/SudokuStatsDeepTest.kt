package com.vayunmathur.games.sudoku.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stats aggregation and board-state rules beyond the first suite: cross-size
 * best times, sparse per-difficulty records, column/box conflicts, and the
 * given-over-entry precedence of [SudokuGameState.valueAt].
 */
@RunWith(RobolectricTestRunner::class)
class SudokuStatsDeepTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repo: SudokuStatsRepository

    @BeforeTest
    fun setUp() {
        context.getSharedPreferences("sudoku_stats", Context.MODE_PRIVATE).edit().clear().commit()
        repo = SudokuStatsRepository(context)
    }

    @Test
    fun dataStoreFlowsEmitEveryChangeWhileCollected() = runBlocking {
        val store = SudokuDataStore(context)
        // Whatever a previous test left stored, toggling away and back is two real changes.
        val answerStart = store.answerMode.first()
        val answerSeen = async { store.answerMode.take(3).toList() }
        store.setAnswerMode(!answerStart)
        store.setAnswerMode(answerStart)
        assertEquals(listOf(answerStart, !answerStart, answerStart), answerSeen.await())

        val highlightStart = store.highlightMistakes.first()
        val highlightSeen = async { store.highlightMistakes.take(3).toList() }
        store.setHighlightMistakes(!highlightStart)
        store.setHighlightMistakes(highlightStart)
        assertEquals(listOf(highlightStart, !highlightStart, highlightStart), highlightSeen.await())
    }

    @Test
    fun bestTimeIsTheFastestWinForThatDifficultyAcrossSizes() {
        repo.recordGameWon(BoardSize.SIX, Difficulty.EASY, 500)
        repo.recordGameWon(BoardSize.NINE, Difficulty.EASY, 300)
        repo.recordGameWon(BoardSize.TWELVE, Difficulty.EASY, 400)
        repo.recordGameWon(BoardSize.NINE, Difficulty.HARD, 250)
        assertEquals(300, repo.getBestTime(Difficulty.EASY))
        assertEquals(250, repo.getBestTime(Difficulty.HARD))
        assertNull(repo.getBestTime(Difficulty.MEDIUM))
        // A loss records no time and must not disturb the best.
        repo.recordGameLost(BoardSize.NINE, Difficulty.EASY)
        assertEquals(300, repo.getBestTime(Difficulty.EASY))
    }

    @Test
    fun sizeStatsWithSparseDifficultiesTakeMaxStreakAndMinTime() {
        repo.recordGameWon(BoardSize.NINE, Difficulty.EASY, 600)
        repo.recordGameWon(BoardSize.NINE, Difficulty.EASY, 550)
        repo.recordGameWon(BoardSize.NINE, Difficulty.EXPERT, 200)
        // Only EASY and EXPERT have records; the aggregate must not invent the others.
        val agg = repo.getSizeStats(BoardSize.NINE)
        assertEquals(3, agg.gamesWon)
        assertEquals(0, agg.gamesPlayed, "wins recorded directly do not count as played")
        assertEquals(2, agg.currentWinStreak, "the best current streak, not the sum")
        assertEquals(2, agg.bestWinStreak)
        assertEquals(200, agg.bestTimeSeconds)
        assertEquals(2, repo.getBestWinStreak())
        assertEquals(3, repo.getTotalGamesWon())
    }

    @Test
    fun streakAcrossSizesIsTheBestSingleSizeStreak() {
        repeat(3) { repo.recordGameWon(BoardSize.SIX, Difficulty.MEDIUM, 100 + it) }
        repeat(2) { repo.recordGameWon(BoardSize.TWELVE, Difficulty.MEDIUM, 800 + it) }
        repo.recordGameLost(BoardSize.SIX, Difficulty.MEDIUM)
        assertEquals(3, repo.getBestWinStreak(), "best streak is historical: the 6x6 record of 3 stands")
        assertEquals(0, repo.getStats(BoardSize.SIX, Difficulty.MEDIUM).currentWinStreak)
        assertEquals(3, repo.getStats(BoardSize.SIX, Difficulty.MEDIUM).bestWinStreak)
        assertEquals(5, repo.getTotalGamesWon())
    }

    @Test
    fun gameStatsSerializesAndDefaultsRoundTrip() {
        val stats = GameStats(gamesPlayed = 4, gamesWon = 3, currentWinStreak = 2, bestWinStreak = 2, bestTimeSeconds = 77)
        // The repository's own Json configuration, which tolerates unknown keys.
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<GameStats>(json.encodeToString(GameStats.serializer(), stats))
        assertEquals(stats, decoded)
        // Unknown keys from a newer version must not break decoding of an older record.
        val withExtra = json.decodeFromString<GameStats>("""{"gamesPlayed":1,"futureField":true}""")
        assertEquals(1, withExtra.gamesPlayed)
        assertEquals(Int.MAX_VALUE, withExtra.bestTimeSeconds)
    }

    @Test
    fun valueAtPrefersTheGivenOverAnyStoredEntry() {
        val givens = MutableList(36) { 0 }.also { it[10] = 5 }
        val entries = MutableList(36) { 0 }.also { it[10] = 2; it[11] = 3 }
        val state = SudokuGameState(
            size = BoardSize.SIX, difficulty = Difficulty.EASY,
            givens = givens, solution = List(36) { 1 },
            entries = entries, notes = List(36) { 0 },
        )
        assertEquals(5, state.valueAt(10), "the printed clue always wins over a stored entry")
        assertEquals(3, state.valueAt(11))
        assertTrue(state.isGiven(10))
        assertFalse(state.isGiven(11))
    }

    @Test
    fun conflictsAreFoundInColumnsAndBoxesNotJustRows() {
        fun stateWith(vararg placements: Pair<Int, Int>): SudokuGameState {
            val entries = MutableList(36) { 0 }
            placements.forEach { (index, digit) -> entries[index] = digit }
            return SudokuGameState(
                size = BoardSize.SIX, difficulty = Difficulty.EASY,
                givens = List(36) { 0 }, solution = List(36) { 1 },
                entries = entries, notes = List(36) { 0 },
            )
        }
        // Same column (0 and 12 are two rows apart in column 0), different rows and boxes.
        assertEquals(setOf(0, 12), stateWith(0 to 4, 12 to 4).conflictingIndices())
        // Same 2x3 box (0 and 7), different row and column.
        assertEquals(setOf(0, 7), stateWith(0 to 6, 7 to 6).conflictingIndices())
        // Different row, column and box: no conflict even with the same digit.
        assertTrue(stateWith(0 to 4, 15 to 4).conflictingIndices().isEmpty())
        // Three copies of one digit in a row mark all three cells.
        assertEquals(setOf(0, 1, 2), stateWith(0 to 3, 1 to 3, 2 to 3).conflictingIndices())
    }

    @Test
    fun digitCompleteCountsDuplicatesAndOvershoot() {
        val entries = MutableList(36) { 0 }
        // Seven copies of 5 on a 6x6 (one too many): complete, and never negative-counted.
        for (index in 0..6) entries[index] = 5
        val state = SudokuGameState(
            size = BoardSize.SIX, difficulty = Difficulty.EASY,
            givens = List(36) { 0 }, solution = List(36) { 1 },
            entries = entries, notes = List(36) { 0 },
        )
        assertTrue(state.isDigitComplete(5))
        assertFalse(state.isDigitComplete(4))
        assertEquals(36 - 7, state.blankIndices().size)
    }

    @Test
    fun everyClueOfACompleteBoardMatchesAndWrongListIsSorted() {
        val solution = (0 until 36).map { (it % 6) + 1 }
        val state = SudokuGameState(
            size = BoardSize.SIX, difficulty = Difficulty.EASY,
            givens = List(36) { 0 }, solution = solution,
            entries = solution.toMutableList().also { it[20] = 1; it[3] = 2 },
            notes = List(36) { 0 },
        )
        // Entries were copied from the solution, so indices 3 and 20 are wrong only
        // if the replacement differs there; compute the expectation from the data.
        val expectedWrong = state.entries.indices.filter { state.entries[it] != solution[it] }
        assertEquals(expectedWrong, state.wrongIndices())
        assertEquals(expectedWrong.isEmpty(), state.isComplete)
    }
}
