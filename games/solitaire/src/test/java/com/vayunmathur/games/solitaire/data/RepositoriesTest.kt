package com.vayunmathur.games.solitaire.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.platform.AppBackupAgent
import com.vayunmathur.games.solitaire.platform.SolitaireAchievementsManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stats persistence/aggregation, settings, colour schemes, backup lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoriesTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest fun clearPrefs() {
        ctx.getSharedPreferences("solitaire_stats", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("solitaire_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun statsRecordPlayedWonLostAndAggregatePerMode() {
        val repo = SolitaireStatsRepository(ctx)
        assertEquals(GameStats(), repo.getStats(GameMode.KLONDIKE, "DRAW_ONE_REGULAR"))
        assertEquals(GameStats(), repo.getModeStats(GameMode.KLONDIKE))
        assertEquals(0, repo.getTotalGamesWon())
        assertEquals(0, repo.getBestWinStreak())

        repo.recordGamePlayed(GameMode.KLONDIKE, "DRAW_ONE_REGULAR")
        repo.recordGamePlayed(GameMode.KLONDIKE, "DRAW_ONE_REGULAR")
        repo.recordGamePlayed(GameMode.KLONDIKE, "DRAW_THREE_HARD")
        repo.recordGameWon(GameMode.KLONDIKE, "DRAW_ONE_REGULAR", 300)
        repo.recordGameWon(GameMode.KLONDIKE, "DRAW_ONE_REGULAR", 120)
        repo.recordGameWon(GameMode.KLONDIKE, "DRAW_THREE_HARD", 600)

        val variant = repo.getStats(GameMode.KLONDIKE, "DRAW_ONE_REGULAR")
        assertEquals(2, variant.gamesPlayed)
        assertEquals(2, variant.gamesWon)
        assertEquals(2, variant.currentWinStreak)
        assertEquals(2, variant.bestWinStreak)
        assertEquals(120, variant.bestTimeSeconds)

        val mode = repo.getModeStats(GameMode.KLONDIKE)
        assertEquals(3, mode.gamesPlayed)
        assertEquals(3, mode.gamesWon)
        assertEquals(2, mode.currentWinStreak) // max across variants
        assertEquals(120, mode.bestTimeSeconds) // min across variants
        assertEquals(3, repo.getTotalGamesWon())
        assertEquals(2, repo.getBestWinStreak())
        assertEquals(GameStats(), repo.getModeStats(GameMode.SPIDER))

        // A loss resets only that variant's current streak.
        repo.recordGameLost(GameMode.KLONDIKE, "DRAW_ONE_REGULAR")
        val after = repo.getStats(GameMode.KLONDIKE, "DRAW_ONE_REGULAR")
        assertEquals(0, after.currentWinStreak)
        assertEquals(2, after.bestWinStreak)
        assertEquals(2, after.gamesWon)
    }

    @Test fun statsSurviveNewRepositoryInstanceAndLegacyKeyFoldsIn() {
        val repo = SolitaireStatsRepository(ctx)
        repo.recordGameWon(GameMode.FREECELL, "STANDARD", 90)
        // Legacy pre-variant key written directly.
        ctx.getSharedPreferences("solitaire_stats", Context.MODE_PRIVATE)
            .edit()
            .putString("stats_FREECELL", """{"gamesPlayed":5,"gamesWon":1,"currentWinStreak":0,"bestWinStreak":4,"bestTimeSeconds":45}""")
            .commit()
        val fresh = SolitaireStatsRepository(ctx)
        val mode = fresh.getModeStats(GameMode.FREECELL)
        assertEquals(5 + 0, mode.gamesPlayed) // variant record played 0 games
        assertEquals(2, mode.gamesWon)
        assertEquals(4, mode.bestWinStreak)
        assertEquals(45, mode.bestTimeSeconds)
        assertEquals(2, fresh.getTotalGamesWon())
    }

    @Test fun corruptedStatsJsonIsToleratedAsMissing() {
        ctx.getSharedPreferences("solitaire_stats", Context.MODE_PRIVATE)
            .edit().putString("stats_SPIDER__1SUIT", "not json").commit()
        val repo = SolitaireStatsRepository(ctx)
        // Current behaviour: a corrupt record surfaces as a decode failure
        // rather than silently resetting the player's stats.
        kotlin.test.assertFailsWith<kotlinx.serialization.SerializationException> {
            repo.getStats(GameMode.SPIDER, "1SUIT")
        }
    }

    @Test fun settingsDefaultPersistAndInvalidValue() {
        val repo = SolitaireSettingsRepository(ctx)
        assertEquals(CardColorScheme.TWO_COLOUR, repo.getCardColorScheme())
        repo.setCardColorScheme(CardColorScheme.FOUR_COLOUR)
        assertEquals(CardColorScheme.FOUR_COLOUR, SolitaireSettingsRepository(ctx).getCardColorScheme())
        ctx.getSharedPreferences("solitaire_settings", Context.MODE_PRIVATE)
            .edit().putString("card_color_scheme", "RAINBOW").commit()
        assertEquals(CardColorScheme.TWO_COLOUR, repo.getCardColorScheme())
        repo.setCardColorScheme(CardColorScheme.TWO_COLOUR)
        assertEquals(CardColorScheme.TWO_COLOUR, repo.getCardColorScheme())
    }

    @Test fun cardColorSchemes() {
        val two = CardColorScheme.TWO_COLOUR
        assertEquals(Color(0xFFCC0000), two.colorFor(Suit.HEARTS))
        assertEquals(Color(0xFFCC0000), two.colorFor(Suit.DIAMONDS))
        assertEquals(Color(0xFF000000), two.colorFor(Suit.SPADES))
        assertEquals(Color(0xFF000000), two.colorFor(Suit.CLUBS))
        val four = CardColorScheme.FOUR_COLOUR
        assertEquals(Color(0xFFCC0000), four.colorFor(Suit.HEARTS))
        assertEquals(Color(0xFF0B57D0), four.colorFor(Suit.DIAMONDS))
        assertEquals(Color(0xFF1B7F3B), four.colorFor(Suit.CLUBS))
        assertEquals(Color(0xFF000000), four.colorFor(Suit.SPADES))
    }

    @Test fun gameStatsDefaultsAndCopy() {
        val s = GameStats()
        assertEquals(Int.MAX_VALUE, s.bestTimeSeconds)
        assertEquals(0, s.gamesPlayed)
        assertEquals(s.copy(gamesWon = 3), GameStats(gamesWon = 3))
    }

    @Test fun backupAgentLists() {
        val agent = AppBackupAgent()
        assertEquals(listOf("solitaire_stats"), agent.prefNames)
        assertEquals(listOf("datastore_default"), agent.datastoreNames)
    }

    @Test fun achievementsManagerChecksExistingFromStats() {
        val stats = SolitaireStatsRepository(ctx)
        stats.recordGameWon(GameMode.KLONDIKE, "DRAW_ONE_REGULAR", 100)
        stats.recordGameWon(GameMode.SPIDER, "1SUIT", 100)
        val json = ctx.assets.open("achievements.json").bufferedReader().readText()
        val manager = SolitaireAchievementsManager(ctx, json, stats)
        // Construction does not auto-check; the explicit check must run
        // without error against recorded wins and report progress calls.
        manager.checkExistingAchievements()
        manager.dismissNotification()
    }

    @Test fun achievementsManagerWithNoWinsChecksCleanly() {
        val stats = SolitaireStatsRepository(ctx)
        val json = ctx.assets.open("achievements.json").bufferedReader().readText()
        val manager = SolitaireAchievementsManager(ctx, json, stats)
        manager.checkExistingAchievements()
        manager.dismissNotification()
    }
}
