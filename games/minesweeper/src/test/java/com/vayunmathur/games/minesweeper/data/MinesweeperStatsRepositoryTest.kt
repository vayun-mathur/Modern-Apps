package com.vayunmathur.games.minesweeper.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Per-variant stats: played/won/streak/best-time recording and the per-size aggregation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MinesweeperStatsRepositoryTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private fun repo() = MinesweeperStatsRepository(app)

    @BeforeTest
    fun clearStats() {
        app.getSharedPreferences("minesweeper_stats", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun untouchedVariantReadsAsEmptyStats() {
        val stats = repo().getStats(BoardSize.LARGE, Difficulty.EXPERT)
        assertEquals(GameStats(), stats)
        assertEquals(Int.MAX_VALUE, stats.bestTimeSeconds)
        assertEquals(GameStats(), repo().getSizeStats(BoardSize.MEDIUM))
        assertEquals(0, repo().getTotalGamesWon())
        assertEquals(0, repo().getBestWinStreak())
    }

    @Test
    fun playedOnlyCountsTheVariantPlayed() {
        val repo = repo()
        repo.recordGamePlayed(BoardSize.SMALL, Difficulty.EASY)
        repo.recordGamePlayed(BoardSize.SMALL, Difficulty.EASY)
        repo.recordGamePlayed(BoardSize.SMALL, Difficulty.HARD)

        assertEquals(2, repo.getStats(BoardSize.SMALL, Difficulty.EASY).gamesPlayed)
        assertEquals(1, repo.getStats(BoardSize.SMALL, Difficulty.HARD).gamesPlayed)
        assertEquals(0, repo.getStats(BoardSize.MEDIUM, Difficulty.EASY).gamesPlayed)
        // Playing alone never touches the win counters.
        val easy = repo.getStats(BoardSize.SMALL, Difficulty.EASY)
        assertEquals(0, easy.gamesWon)
        assertEquals(0, easy.currentWinStreak)
    }

    @Test
    fun winsBuildStreaksAndKeepTheBestTime() {
        val repo = repo()
        repo.recordGameWon(BoardSize.MEDIUM, Difficulty.MEDIUM, 90)
        repo.recordGameWon(BoardSize.MEDIUM, Difficulty.MEDIUM, 45)
        repo.recordGameWon(BoardSize.MEDIUM, Difficulty.MEDIUM, 120)

        val stats = repo.getStats(BoardSize.MEDIUM, Difficulty.MEDIUM)
        assertEquals(3, stats.gamesWon)
        assertEquals(3, stats.currentWinStreak)
        assertEquals(3, stats.bestWinStreak)
        assertEquals(45, stats.bestTimeSeconds, "a slower later win must not erase the best time")
    }

    @Test
    fun aLossResetsTheCurrentStreakButKeepsBestStreakAndWins() {
        val repo = repo()
        repeat(3) { repo.recordGameWon(BoardSize.SMALL, Difficulty.HARD, 30) }
        repo.recordGameLost(BoardSize.SMALL, Difficulty.HARD)

        val stats = repo.getStats(BoardSize.SMALL, Difficulty.HARD)
        assertEquals(0, stats.currentWinStreak)
        assertEquals(3, stats.bestWinStreak)
        assertEquals(3, stats.gamesWon)
        assertEquals(30, stats.bestTimeSeconds)

        // The next win starts a new streak from one, not from the old best.
        repo.recordGameWon(BoardSize.SMALL, Difficulty.HARD, 25)
        val after = repo.getStats(BoardSize.SMALL, Difficulty.HARD)
        assertEquals(1, after.currentWinStreak)
        assertEquals(3, after.bestWinStreak)
        assertEquals(25, after.bestTimeSeconds)
    }

    @Test
    fun sizeStatsSumPlayedAndWonAndTakeBestOfTheRest() {
        val repo = repo()
        repo.recordGamePlayed(BoardSize.LARGE, Difficulty.EASY)
        repo.recordGameWon(BoardSize.LARGE, Difficulty.EASY, 80)
        repo.recordGameWon(BoardSize.LARGE, Difficulty.EASY, 70)
        repo.recordGamePlayed(BoardSize.LARGE, Difficulty.EXPERT)
        repo.recordGamePlayed(BoardSize.LARGE, Difficulty.EXPERT)
        repo.recordGameWon(BoardSize.LARGE, Difficulty.EXPERT, 55)
        // A different size must not leak into the LARGE aggregate.
        repo.recordGameWon(BoardSize.SMALL, Difficulty.EASY, 5)

        val size = repo.getSizeStats(BoardSize.LARGE)
        assertEquals(3, size.gamesPlayed)
        assertEquals(3, size.gamesWon)
        assertEquals(2, size.currentWinStreak, "the best current streak across densities")
        assertEquals(2, size.bestWinStreak)
        assertEquals(55, size.bestTimeSeconds, "the fastest time across densities")
    }

    @Test
    fun totalsSpanEverySizeAndDensity() {
        val repo = repo()
        repo.recordGameWon(BoardSize.SMALL, Difficulty.EASY, 10)
        repo.recordGameWon(BoardSize.SMALL, Difficulty.EASY, 10)
        repo.recordGameWon(BoardSize.MEDIUM, Difficulty.HARD, 10)
        repeat(4) { repo.recordGameWon(BoardSize.LARGE, Difficulty.MEDIUM, 10) }
        repo.recordGameLost(BoardSize.LARGE, Difficulty.MEDIUM)
        repeat(2) { repo.recordGameWon(BoardSize.LARGE, Difficulty.MEDIUM, 10) }

        assertEquals(9, repo.getTotalGamesWon())
        // LARGE/MEDIUM peaked at four in a row before the loss broke the run.
        assertEquals(4, repo.getBestWinStreak())
    }

    @Test
    fun recordsSurviveANewRepositoryInstance() {
        repo().recordGamePlayed(BoardSize.MEDIUM, Difficulty.EXPERT)
        repo().recordGameWon(BoardSize.MEDIUM, Difficulty.EXPERT, 33)

        val reread = repo().getStats(BoardSize.MEDIUM, Difficulty.EXPERT)
        assertEquals(1, reread.gamesPlayed)
        assertEquals(1, reread.gamesWon)
        assertEquals(33, reread.bestTimeSeconds)
        assertTrue(reread.currentWinStreak == 1)
    }
}
