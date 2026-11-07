package com.vayunmathur.sdk.games

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GameHubContractTest {

    @Test
    fun authoritiesAndPackages() {
        assertEquals("com.vayunmathur.games.hub.provider", GameHubContract.AUTHORITY)
        assertEquals("com.vayunmathur.games.provider", GameHubContract.LEGACY_AUTHORITY)
        assertEquals("com.vayunmathur.games.hub", GameHubContract.HUB_PACKAGE)
        assertEquals("com.vayunmathur.games", GameHubContract.LEGACY_HUB_PACKAGE)
        assertEquals("content://com.vayunmathur.games.hub.provider", GameHubContract.BASE_URI.toString())
        assertEquals("content://com.vayunmathur.games.provider", GameHubContract.LEGACY_BASE_URI.toString())
    }

    @Test
    fun uriBuilders() {
        val base = "content://com.vayunmathur.games.hub.provider"
        assertEquals("$base/games", GameHubContract.buildGamesUri().toString())
        assertEquals("$base/games/chess", GameHubContract.buildGameUri("chess").toString())
        assertEquals("$base/achievements/chess/defs", GameHubContract.buildAchievementDefsUri("chess").toString())
        assertEquals("$base/achievements/chess/progress", GameHubContract.buildAchievementProgressUri("chess").toString())
        assertEquals(
            "$base/achievements/chess/progress/first_win",
            GameHubContract.buildAchievementProgressItemUri("chess", "first_win").toString(),
        )
        assertEquals("$base/sessions/chess", GameHubContract.buildSessionsUri("chess").toString())
        assertEquals("$base/sessions/chess/s-1", GameHubContract.buildSessionItemUri("chess", "s-1").toString())
        assertEquals("$base/streaks/chess", GameHubContract.buildStreakUri("chess").toString())
        assertEquals("$base/achievements/chess", GameHubContract.buildLegacyAchievementsUri("chess").toString())
        assertEquals("$base/achievements/chess/first_win", GameHubContract.buildLegacyAchievementItemUri("chess", "first_win").toString())
    }

    @Test
    fun columnNamesAreStable() {
        assertEquals("game_id", GameHubContract.Games.GAME_ID)
        assertEquals("package_name", GameHubContract.Games.PACKAGE_NAME)
        assertEquals("achievement_id", GameHubContract.AchievementDefs.ACHIEVEMENT_ID)
        assertEquals("xp_reward", GameHubContract.AchievementDefs.XP_REWARD)
        assertEquals("is_secret", GameHubContract.AchievementDefs.IS_SECRET)
        assertEquals("progress", GameHubContract.AchievementProgressCols.PROGRESS)
        assertEquals("is_unlocked", GameHubContract.AchievementProgressCols.IS_UNLOCKED)
        assertEquals("session_id", GameHubContract.Sessions.SESSION_ID)
        assertEquals("duration_ms", GameHubContract.Sessions.DURATION_MS)
        assertEquals("current_streak", GameHubContract.Streaks.CURRENT_STREAK)
        assertEquals("last_completed_day", GameHubContract.Streaks.LAST_COMPLETED_DAY)
        assertEquals("unlocked", GameHubContract.Legacy.UNLOCKED)
        assertEquals("icon_res_name", GameHubContract.Legacy.ICON_RES_NAME)
    }

    @Test
    fun modelsAndTiers() {
        assertEquals(10, AchievementTier.BRONZE.defaultXp)
        assertEquals(25, AchievementTier.SILVER.defaultXp)
        assertEquals(50, AchievementTier.GOLD.defaultXp)
        assertEquals(100, AchievementTier.PLATINUM.defaultXp)
        assertEquals(
            listOf(10, 25, 50, 100),
            AchievementTier.entries.map { it.defaultXp },
        )
        val meta = GameMetadata(gameId = "chess", displayName = "Chess")
        assertEquals(null, meta.description)
        assertEquals(null, meta.versionCode)
        val def = AchievementDefinition(id = "a1", name = "First", description = "win")
        assertEquals(25, def.xpReward)
        assertEquals(1, def.targetProgress)
        assertEquals(AchievementTier.BRONZE, def.tier)
        assertTrue(!def.isSecret)
        val progress = AchievementProgress(achievementId = "a1")
        assertEquals(0, progress.progress)
        assertEquals(null, progress.unlockedAt)
        val streak = DailyStreak(currentStreak = 3, longestStreak = 9, lastCompletedDay = 20_000L)
        assertEquals(9, streak.longestStreak)
        // Data-class semantics.
        assertEquals(def, def.copy())
        assertEquals(def.hashCode(), def.copy().hashCode())
        assertTrue(GameHubNotInstalledException().message!!.contains("not installed"))
        assertEquals("chess", ScoreEntry("chess", "blitz", 5L, 6L).gameId)
    }
}
