package com.vayunmathur.sdk.games

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GameHubClientTest {

    private lateinit var context: Context
    private lateinit var client: GameHubClient

    @BeforeTest
    fun setUp() {
        FakeHubProvider.reset()
        context = ApplicationProvider.getApplicationContext()
        Robolectric.setupContentProvider(FakeHubProvider::class.java, GameHubContract.AUTHORITY)
        client = GameHubClient(context, "chess")
    }

    private fun installHub() = installHubPackage(context.packageManager, GameHubContract.HUB_PACKAGE)

    // ---- Hub absent: everything is a fail-soft no-op ----

    @Test
    fun hubAbsentMakesEverythingNoOp() = runTest {
        assertFalse(client.isHubInstalled())
        assertFalse(client.registerGame(GameMetadata("chess", "Chess")))
        client.registerAchievements(listOf(AchievementDefinition("a1", "First", "win")))
        client.syncAchievementProgress(listOf(AchievementProgress("a1", progress = 1, isUnlocked = true)))
        assertFalse(client.unlockAchievement("a1"))
        assertFalse(client.reportProgress("a1", 1))
        assertFalse(client.incrementProgress("a1", 1))
        assertNull(client.startSession())
        assertFalse(client.endSession("s-1"))
        assertFalse(client.reportDailyStreak(DailyStreak(1, 1, 1L)))
        assertTrue(FakeHubProvider.inserts.isEmpty())
        assertTrue(FakeHubProvider.queries.isEmpty())
    }

    @Test
    fun legacyHubPackageAlsoCountsAsInstalled() {
        installHubPackage(context.packageManager, GameHubContract.LEGACY_HUB_PACKAGE)
        assertTrue(client.isHubInstalled())
    }

    // ---- Hub present ----

    @Test
    fun registerGameInsertsGameRow() = runTest {
        installHub()
        assertTrue(client.isHubInstalled())
        val ok = client.registerGame(
            GameMetadata("chess", "Chess", description = "board", versionName = "1.2", versionCode = 7L),
        )
        assertTrue(ok)
        val insert = FakeHubProvider.inserts.single()
        assertEquals(GameHubContract.buildGameUri("chess"), insert.uri)
        assertEquals("chess", insert.values[GameHubContract.Games.GAME_ID])
        assertEquals("Chess", insert.values[GameHubContract.Games.DISPLAY_NAME])
        assertEquals("board", insert.values[GameHubContract.Games.DESCRIPTION])
        assertEquals("1.2", insert.values[GameHubContract.Games.VERSION_NAME])
        assertEquals(7L, insert.values[GameHubContract.Games.VERSION_CODE])
        assertEquals(context.packageName, insert.values[GameHubContract.Games.PACKAGE_NAME])
        assertNotNull(insert.values[GameHubContract.Games.REGISTERED_AT])
    }

    @Test
    fun registerGameReturnsFalseWhenInsertReturnsNullOrThrows() = runTest {
        installHub()
        FakeHubProvider.insertReturnsNull = true
        assertFalse(client.registerGame(GameMetadata("chess", "Chess")))
        FakeHubProvider.throwOnInsert = true
        assertFalse(client.registerGame(GameMetadata("chess", "Chess")))
    }

    @Test
    fun registerAchievementsInsertsEachDefinition() = runTest {
        installHub()
        client.registerAchievements(
            listOf(
                AchievementDefinition("a1", "First", "win", xpReward = 50, targetProgress = 3, isSecret = true, tier = AchievementTier.GOLD, iconResName = "ic_a"),
                AchievementDefinition("a2", "Second", "win twice"),
            ),
        )
        assertEquals(2, FakeHubProvider.inserts.size)
        val first = FakeHubProvider.inserts[0]
        assertEquals(GameHubContract.buildAchievementDefsUri("chess"), first.uri)
        assertEquals("a1", first.values[GameHubContract.AchievementDefs.ACHIEVEMENT_ID])
        assertEquals(50, first.values[GameHubContract.AchievementDefs.XP_REWARD])
        assertEquals(3, first.values[GameHubContract.AchievementDefs.TARGET_PROGRESS])
        assertEquals(1, first.values[GameHubContract.AchievementDefs.IS_SECRET])
        assertEquals("GOLD", first.values[GameHubContract.AchievementDefs.TIER])
        assertEquals("ic_a", first.values[GameHubContract.AchievementDefs.ICON_RES_NAME])
        assertEquals(0, FakeHubProvider.inserts[1].values[GameHubContract.AchievementDefs.IS_SECRET])
        // Empty list is a no-op (after the hub check).
        FakeHubProvider.inserts.clear()
        client.registerAchievements(emptyList())
        assertTrue(FakeHubProvider.inserts.isEmpty())
        // A throwing provider is swallowed per definition.
        FakeHubProvider.throwOnInsert = true
        client.registerAchievements(listOf(AchievementDefinition("a3", "Third", "x")))
    }

    @Test
    fun syncAchievementProgressInsertsStatuses() = runTest {
        installHub()
        client.syncAchievementProgress(
            listOf(AchievementProgress("a1", progress = 2, isUnlocked = true, unlockedAt = 99L)),
        )
        val insert = FakeHubProvider.inserts.single()
        assertEquals(GameHubContract.buildAchievementProgressUri("chess"), insert.uri)
        assertEquals(2, insert.values[GameHubContract.AchievementProgressCols.PROGRESS])
        assertEquals(1, insert.values[GameHubContract.AchievementProgressCols.IS_UNLOCKED])
        assertEquals(99L, insert.values[GameHubContract.AchievementProgressCols.UNLOCKED_AT])
        client.syncAchievementProgress(emptyList())
        assertEquals(1, FakeHubProvider.inserts.size)
    }

    @Test
    fun unlockFallsBackToUpdateWhenInsertReturnsNull() = runTest {
        installHub()
        assertTrue(client.unlockAchievement("a1"))
        assertEquals(GameHubContract.buildAchievementProgressItemUri("chess", "a1"), FakeHubProvider.inserts[0].uri)
        FakeHubProvider.insertReturnsNull = true
        FakeHubProvider.updateCount = 1
        assertTrue(client.unlockAchievement("a1"))
        assertEquals(1, FakeHubProvider.updates.size)
        FakeHubProvider.updateCount = 0
        assertFalse(client.unlockAchievement("a1"))
        FakeHubProvider.throwOnUpdate = true
        assertFalse(client.unlockAchievement("a1"))
    }

    @Test
    fun reportProgressInsertAndUpdatePaths() = runTest {
        installHub()
        assertTrue(client.reportProgress("a1", 4))
        assertEquals(4, FakeHubProvider.inserts[0].values[GameHubContract.AchievementProgressCols.PROGRESS])
        FakeHubProvider.insertReturnsNull = true
        assertTrue(client.reportProgress("a1", 5))
        val update = FakeHubProvider.updates.single()
        assertEquals(5, update.values[GameHubContract.AchievementProgressCols.PROGRESS])
        // The update fallback carries only progress + timestamp, not the insert's game columns.
        assertNull(update.values[GameHubContract.AchievementProgressCols.GAME_ID])
        FakeHubProvider.throwOnInsert = true
        assertFalse(client.reportProgress("a1", 1))
    }

    @Test
    fun incrementProgressAddsToExisting() = runTest {
        installHub()
        FakeHubProvider.existingProgress = 5
        assertTrue(client.incrementProgress("a1", 3))
        assertEquals(8, FakeHubProvider.inserts.last().values[GameHubContract.AchievementProgressCols.PROGRESS])
        // No existing row: starts from 0.
        FakeHubProvider.existingProgress = null
        FakeHubProvider.inserts.clear()
        assertTrue(client.incrementProgress("a1", 2))
        assertEquals(2, FakeHubProvider.inserts.last().values[GameHubContract.AchievementProgressCols.PROGRESS])
        // Query failure is ignored and still reports the delta from 0.
        FakeHubProvider.throwOnQuery = true
        FakeHubProvider.throwOnInsert = false
        assertTrue(client.incrementProgress("a1", 1))
    }

    @Test
    fun sessionsStartAndEnd() = runTest {
        installHub()
        val sessionId = client.startSession()
        assertNotNull(sessionId)
        val insert = FakeHubProvider.inserts.last()
        assertEquals(GameHubContract.buildSessionItemUri("chess", sessionId), insert.uri)
        assertEquals(sessionId, insert.values[GameHubContract.Sessions.SESSION_ID])
        assertTrue(client.endSession(sessionId))
        assertEquals(insert.uri, FakeHubProvider.updates.last().uri)
        assertNotNull(FakeHubProvider.updates.last().values[GameHubContract.Sessions.END_TIME])
        FakeHubProvider.updateCount = 0
        assertFalse(client.endSession(sessionId))
        FakeHubProvider.insertReturnsNull = true
        assertNull(client.startSession())
        FakeHubProvider.throwOnInsert = true
        assertNull(client.startSession())
    }

    @Test
    fun dailyStreakIsPushed() = runTest {
        installHub()
        assertTrue(client.reportDailyStreak(DailyStreak(currentStreak = 4, longestStreak = 9, lastCompletedDay = 20_001L)))
        val insert = FakeHubProvider.inserts.single()
        assertEquals(GameHubContract.buildStreakUri("chess"), insert.uri)
        assertEquals(4, insert.values[GameHubContract.Streaks.CURRENT_STREAK])
        assertEquals(9, insert.values[GameHubContract.Streaks.LONGEST_STREAK])
        assertEquals(20_001L, insert.values[GameHubContract.Streaks.LAST_COMPLETED_DAY])
        FakeHubProvider.throwOnInsert = true
        assertFalse(client.reportDailyStreak(DailyStreak(1, 1, 1L)))
    }

    @Test
    @Suppress("DEPRECATION")
    fun legacyRegistrationAndScoreNoOps() = runTest {
        installHub()
        client.registerAchievementsLegacy(listOf(Achievement("a1", "First", "win", iconResName = "ic")))
        assertEquals(1, FakeHubProvider.inserts.size)
        assertEquals(25, FakeHubProvider.inserts[0].values[GameHubContract.AchievementDefs.XP_REWARD])
        client.reportScore("blitz", 100L) // no-op, must not reach the provider
        assertEquals(1, FakeHubProvider.inserts.size)
    }
}
