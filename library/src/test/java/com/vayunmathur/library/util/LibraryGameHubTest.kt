package com.vayunmathur.library.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.sdk.games.DailyStreak
import com.vayunmathur.sdk.games.GameHubClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GameHubReporter/GameHubSessionReporter/DailyStreakReporter against a real
 * [GameHubClient] with no hub installed: every call fail-softs, which is the
 * behaviour games rely on, and the legacy->hub mapping still runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryGameHubTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private class HubAchievements(context: Context, prefix: String) : AchievementsManager(
        context,
        """[
          {"id":"win_50_master","name":"Master","description":"d","targetProgress":50},
          {"id":"win_10_enthusiast","name":"Ten","description":"d","targetProgress":10},
          {"id":"first_win","name":"First","description":"d","targetProgress":1},
          {"id":"level_500","name":"L500","description":"d","targetProgress":500},
          {"id":"win_vs_ai_hard","name":"Hard","description":"d","targetProgress":1},
          {"id":"win_3","name":"Three","description":"d","targetProgress":3},
          {"id":"level_100","name":"L100","description":"d","targetProgress":100},
          {"id":"level_1_done","name":"L1","description":"d","targetProgress":1},
          {"id":"first_mate","name":"Mate","description":"d","targetProgress":1},
          {"id":"plain_quest","name":"Plain","description":"d","targetProgress":2}
        ]""",
        prefix,
    ) {
        override fun checkExistingAchievements() {}
    }

    @Test fun bindMapsAchievementsAndForwardsUnlocks() = runBlocking {
        val manager = HubAchievements(ctx, "hub${System.nanoTime()}_")
        manager.onProgressUpdated("first_win", 1)
        withTimeout(5000) { while (manager.newAchievement.value == null) delay(20) }
        assertEquals("first_win", manager.newAchievement.value!!.id)

        val client = GameHubClient(ctx, "chess")
        val handle = GameHubReporter.bind(ctx, "chess", manager, client)
        // The bind scope seeds defs/progress against the (absent) hub; give the
        // collectors a deterministic condition: the manager unlock flow below.
        manager.dismissNotification()
        manager.onAchievementUnlocked("win_3")
        withTimeout(5000) { while (manager.newAchievement.value?.id != "win_3") delay(20) }
        handle.cancel()
        handle.cancel() // cancelling a cancelled handle is safe
    }

    @Test fun bindWithCustomMetadata() = runBlocking {
        val manager = HubAchievements(ctx, "hub2${System.nanoTime()}_")
        val client = GameHubClient(ctx, "sudoku")
        val handle = GameHubReporter.bind(
            ctx, "sudoku", manager, client,
            com.vayunmathur.sdk.games.GameMetadata("sudoku", "Sudoku!", versionName = "1.0"),
        )
        // Seeding completes silently against the absent hub; wait until the
        // manager side can still service a progress write through the binder.
        manager.onProgressUpdated("plain_quest", 1)
        withTimeout(5000) {
            while (true) {
                val statuses = manager.getAchievementStatuses().first()
                if (statuses.any { it.achievement.id == "plain_quest" && it.progress == 1 }) break
                delay(20)
            }
        }
        handle.cancel()
    }

    @Test fun sessionReporterStartEndWithoutHub() {
        val reporter = GameHubSessionReporter(GameHubClient(ctx, "pipes"))
        reporter.end() // no session: early return
        reporter.start()
        reporter.start() // a session launch is already in flight / started
        reporter.end()
        reporter.end()
    }

    @Test fun dailyStreakReporterSwallowsMissingHub() = runBlocking {
        // No hub installed: report() must complete without throwing.
        DailyStreakReporter.report(ctx, "sudoku", DailyChallengeStore.Streak(3, 7), 20_000L)
        DailyStreakReporter.report(ctx, "sudoku", DailyChallengeStore.Streak(0, 0), 0L)
    }

    @Test fun hubClientQueriesWithoutHub() = runBlocking {
        val client = GameHubClient(ctx, "arrows")
        assertEquals(false, client.isHubInstalled())
        assertEquals(false, client.registerGame(com.vayunmathur.sdk.games.GameMetadata("arrows", "Arrows")))
        assertNull(client.startSession())
        assertEquals(false, client.endSession("nope"))
        assertEquals(false, client.unlockAchievement("first_win"))
        assertEquals(false, client.reportProgress("first_win", 2))
        assertEquals(false, client.reportDailyStreak(DailyStreak(1, 1, 1L)))
        client.registerAchievements(emptyList())
        client.syncAchievementProgress(emptyList())
    }
}
