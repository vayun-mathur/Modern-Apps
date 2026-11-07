package com.vayunmathur.games.sudoku.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class SudokuStatsRepositoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repo: SudokuStatsRepository

    @BeforeTest
    fun setUp() {
        context.getSharedPreferences("sudoku_stats", Context.MODE_PRIVATE).edit().clear().commit()
        repo = SudokuStatsRepository(context)
    }

    @Test
    fun defaultsAreEmptyForEveryVariant() {
        for (size in BoardSize.entries) for (difficulty in Difficulty.entries) {
            assertEquals(GameStats(), repo.getStats(size, difficulty))
        }
        assertEquals(GameStats(), repo.getSizeStats(BoardSize.NINE))
        assertEquals(0, repo.getTotalGamesWon())
        assertEquals(0, repo.getBestWinStreak())
        assertNull(repo.getBestTime(Difficulty.EASY))
    }

    @Test
    fun recordPlayedOnlyIncrementsPlayedForThatVariant() {
        repo.recordGamePlayed(BoardSize.SIX, Difficulty.EASY)
        repo.recordGamePlayed(BoardSize.SIX, Difficulty.EASY)
        repo.recordGamePlayed(BoardSize.NINE, Difficulty.HARD)

        assertEquals(2, repo.getStats(BoardSize.SIX, Difficulty.EASY).gamesPlayed)
        assertEquals(1, repo.getStats(BoardSize.NINE, Difficulty.HARD).gamesPlayed)
        assertEquals(0, repo.getStats(BoardSize.SIX, Difficulty.HARD).gamesPlayed)
        assertEquals(0, repo.getStats(BoardSize.SIX, Difficulty.EASY).gamesWon)
    }

    @Test
    fun recordWonUpdatesWinsStreaksAndBestTimeWithMin() {
        repo.recordGameWon(BoardSize.NINE, Difficulty.MEDIUM, 200)
        var stats = repo.getStats(BoardSize.NINE, Difficulty.MEDIUM)
        assertEquals(1, stats.gamesWon)
        assertEquals(1, stats.currentWinStreak)
        assertEquals(1, stats.bestWinStreak)
        assertEquals(200, stats.bestTimeSeconds)

        repo.recordGameWon(BoardSize.NINE, Difficulty.MEDIUM, 350)
        stats = repo.getStats(BoardSize.NINE, Difficulty.MEDIUM)
        assertEquals(2, stats.gamesWon)
        assertEquals(2, stats.currentWinStreak)
        assertEquals(200, stats.bestTimeSeconds, "slower win must not replace best time")

        repo.recordGameWon(BoardSize.NINE, Difficulty.MEDIUM, 120)
        assertEquals(120, repo.getStats(BoardSize.NINE, Difficulty.MEDIUM).bestTimeSeconds)
    }

    @Test
    fun recordLostResetsCurrentStreakButKeepsBestStreakAndWins() {
        repo.recordGameWon(BoardSize.SIX, Difficulty.HARD, 90)
        repo.recordGameWon(BoardSize.SIX, Difficulty.HARD, 80)
        repo.recordGameLost(BoardSize.SIX, Difficulty.HARD)
        val stats = repo.getStats(BoardSize.SIX, Difficulty.HARD)
        assertEquals(0, stats.currentWinStreak)
        assertEquals(2, stats.bestWinStreak)
        assertEquals(2, stats.gamesWon)
        assertEquals(80, stats.bestTimeSeconds)

        // Losing a variant with no history is a harmless no-op record.
        repo.recordGameLost(BoardSize.TWELVE, Difficulty.EASY)
        assertEquals(GameStats(), repo.getStats(BoardSize.TWELVE, Difficulty.EASY).copy(currentWinStreak = 0))
    }

    @Test
    fun sizeStatsAggregatesAcrossDifficulties() {
        repo.recordGamePlayed(BoardSize.SIX, Difficulty.EASY)
        repo.recordGamePlayed(BoardSize.SIX, Difficulty.EXPERT)
        repo.recordGameWon(BoardSize.SIX, Difficulty.EASY, 300)
        repo.recordGameWon(BoardSize.SIX, Difficulty.EXPERT, 500)
        repo.recordGameWon(BoardSize.SIX, Difficulty.EXPERT, 400)
        repo.recordGamePlayed(BoardSize.NINE, Difficulty.EASY)

        val agg = repo.getSizeStats(BoardSize.SIX)
        assertEquals(2, agg.gamesPlayed)
        assertEquals(3, agg.gamesWon)
        assertEquals(2, agg.currentWinStreak, "max current streak across difficulties")
        assertEquals(2, agg.bestWinStreak)
        assertEquals(300, agg.bestTimeSeconds, "min best time across difficulties")
        assertEquals(1, repo.getSizeStats(BoardSize.NINE).gamesPlayed)
        assertEquals(GameStats(), repo.getSizeStats(BoardSize.TWELVE))
    }

    @Test
    fun totalsBestStreakAndBestTimeSpanAllVariants() {
        repo.recordGameWon(BoardSize.SIX, Difficulty.EASY, 111)
        repo.recordGameWon(BoardSize.NINE, Difficulty.EASY, 99)
        repo.recordGameWon(BoardSize.NINE, Difficulty.EASY, 130)
        repo.recordGameWon(BoardSize.TWELVE, Difficulty.EXPERT, 700)

        assertEquals(4, repo.getTotalGamesWon())
        assertEquals(2, repo.getBestWinStreak())
        assertEquals(99, repo.getBestTime(Difficulty.EASY))
        assertEquals(700, repo.getBestTime(Difficulty.EXPERT))
        assertNull(repo.getBestTime(Difficulty.HARD))
    }

    @Test
    fun statsPersistAcrossRepositoryInstances() {
        repo.recordGamePlayed(BoardSize.TWELVE, Difficulty.MEDIUM)
        repo.recordGameWon(BoardSize.TWELVE, Difficulty.MEDIUM, 42)
        val reopened = SudokuStatsRepository(context)
        val stats = reopened.getStats(BoardSize.TWELVE, Difficulty.MEDIUM)
        assertEquals(1, stats.gamesPlayed)
        assertEquals(1, stats.gamesWon)
        assertEquals(42, stats.bestTimeSeconds)
    }

    @Test
    fun gameStatsDefaultsDistinguishNoTimeFromInstant() {
        val stats = GameStats()
        assertEquals(Int.MAX_VALUE, stats.bestTimeSeconds)
        assertEquals(0, stats.gamesPlayed)
        assertEquals(stats, GameStats(0, 0, 0, 0, Int.MAX_VALUE))
    }
}
