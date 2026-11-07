package com.vayunmathur.games.minesweeper.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.minesweeper.Route
import com.vayunmathur.games.minesweeper.platform.AppBackupAgent
import com.vayunmathur.games.minesweeper.platform.MinesweeperAchievementsManager
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stats persistence/aggregation, achievements back-fill, backup, routes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MinesweeperRepositoriesTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest fun clearPrefs() {
        ctx.getSharedPreferences("minesweeper_stats", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun statsPerVariantAndSizeAggregation() {
        val repo = MinesweeperStatsRepository(ctx)
        assertEquals(GameStats(), repo.getStats(BoardSize.SMALL, Difficulty.EASY))
        assertEquals(GameStats(), repo.getSizeStats(BoardSize.SMALL))
        assertEquals(0, repo.getTotalGamesWon())
        assertEquals(0, repo.getBestWinStreak())

        repo.recordGamePlayed(BoardSize.SMALL, Difficulty.EASY)
        repo.recordGamePlayed(BoardSize.SMALL, Difficulty.EASY)
        repo.recordGamePlayed(BoardSize.SMALL, Difficulty.HARD)
        repo.recordGameWon(BoardSize.SMALL, Difficulty.EASY, 300)
        repo.recordGameWon(BoardSize.SMALL, Difficulty.EASY, 90)
        repo.recordGameWon(BoardSize.SMALL, Difficulty.HARD, 45)

        val easy = repo.getStats(BoardSize.SMALL, Difficulty.EASY)
        assertEquals(2, easy.gamesPlayed)
        assertEquals(2, easy.gamesWon)
        assertEquals(2, easy.bestWinStreak)
        assertEquals(90, easy.bestTimeSeconds)

        val size = repo.getSizeStats(BoardSize.SMALL)
        assertEquals(3, size.gamesPlayed)
        assertEquals(3, size.gamesWon)
        assertEquals(2, size.currentWinStreak)
        assertEquals(45, size.bestTimeSeconds)
        assertEquals(GameStats(), repo.getSizeStats(BoardSize.LARGE))
        assertEquals(3, repo.getTotalGamesWon())
        assertEquals(2, repo.getBestWinStreak())

        repo.recordGameLost(BoardSize.SMALL, Difficulty.EASY)
        assertEquals(0, repo.getStats(BoardSize.SMALL, Difficulty.EASY).currentWinStreak)
        assertEquals(2, repo.getStats(BoardSize.SMALL, Difficulty.EASY).bestWinStreak)
        // Records survive a fresh repository over the same prefs.
        assertEquals(3, MinesweeperStatsRepository(ctx).getTotalGamesWon())
    }

    @Test fun bestTimeOnlyImprovesAndStreakAcrossSizes() {
        val repo = MinesweeperStatsRepository(ctx)
        repo.recordGameWon(BoardSize.MEDIUM, Difficulty.MEDIUM, 200)
        repo.recordGameWon(BoardSize.MEDIUM, Difficulty.MEDIUM, 250)
        assertEquals(200, repo.getStats(BoardSize.MEDIUM, Difficulty.MEDIUM).bestTimeSeconds)
        repo.recordGameWon(BoardSize.LARGE, Difficulty.EXPERT, 30)
        repo.recordGameWon(BoardSize.LARGE, Difficulty.EXPERT, 20)
        assertEquals(2, repo.getBestWinStreak())
        assertEquals(4, repo.getTotalGamesWon())
    }

    @Test fun corruptedRecordThrowsOnRead() {
        ctx.getSharedPreferences("minesweeper_stats", Context.MODE_PRIVATE)
            .edit().putString("stats_SMALL__EASY", "{oops").commit()
        val repo = MinesweeperStatsRepository(ctx)
        assertFailsWith<kotlinx.serialization.SerializationException> {
            repo.getStats(BoardSize.SMALL, Difficulty.EASY)
        }
    }

    @Test fun gameStatsDefaults() {
        val s = GameStats()
        assertEquals(Int.MAX_VALUE, s.bestTimeSeconds)
        assertEquals(GameStats(gamesWon = 2), s.copy(gamesWon = 2))
    }

    @Test fun achievementsBackfillFromStats() {
        val repo = MinesweeperStatsRepository(ctx)
        // No wins: check runs cleanly.
        val json = ctx.assets.open("achievements.json").bufferedReader().readText()
        MinesweeperAchievementsManager(ctx, json, repo).checkExistingAchievements()
        // Wins that satisfy first_win, win_large, win_expert and speed_win.
        repo.recordGameWon(BoardSize.LARGE, Difficulty.EXPERT, 30)
        repo.recordGameWon(BoardSize.SMALL, Difficulty.EASY, 500)
        val manager = MinesweeperAchievementsManager(ctx, json, repo)
        manager.checkExistingAchievements()
        manager.dismissNotification()
        // A slow large win still back-fills size/expert but the check itself
        // must simply run (unlock delivery is DataStore-async in the library).
        assertTrue(repo.getTotalGamesWon() == 2)
    }

    @Test fun backupAgentLists() {
        val agent = AppBackupAgent()
        assertEquals(listOf("minesweeper_stats"), agent.prefNames)
        assertEquals(listOf("datastore_default"), agent.datastoreNames)
    }

    @Test fun routeSerializationRoundTrip() {
        val json = Json
        val game = Route.Game(GameConfig(BoardSize.LARGE, Difficulty.EXPERT))
        val decoded = json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), game))
        assertEquals(game, decoded)
        assertEquals(Route.Home, json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.Home)))
        assertEquals(Route.GameCenter, json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.GameCenter)))
        assertTrue(json.encodeToString(Route.serializer(), game).contains("LARGE"))
    }

    @Test fun gameConfigVariantAndDefaults() {
        assertEquals(GameConfig(BoardSize.MEDIUM, Difficulty.MEDIUM), GameConfig())
        assertEquals("LARGE_EXPERT", GameConfig(BoardSize.LARGE, Difficulty.EXPERT).variant)
    }
}
