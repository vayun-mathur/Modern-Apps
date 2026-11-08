package com.vayunmathur.games.arrows.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.arrows.data.ArrowsDataStore
import com.vayunmathur.library.util.DailyChallengeStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ArrowsAchievementsManagerTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun checkExistingWithNoProgressDoesNotCrash() {
        val json = ctx.assets.open("achievements.json").bufferedReader().readText()
        assertTrue(json.contains("first_board"))
        val ds = ArrowsDataStore(ctx)
        val daily = DailyChallengeStore(ctx, "arrows_test_achievements")
        val manager = ArrowsAchievementsManager(ctx, json, ds, daily)
        manager.checkExistingAchievements()
        // Async IO work: give it a moment, then verify manager state queries still work
        Thread.sleep(500)
        assertTrue(manager.achievements.isNotEmpty())
        assertTrue(manager.achievements.any { it.id == "first_board" })
    }

    @Test
    fun checkExistingAfterClearedBoardBackfills() = runBlocking {
        val json = ctx.assets.open("achievements.json").bufferedReader().readText()
        val ds = ArrowsDataStore(ctx)
        ds.recordClearedOnce(-4242L)
        val daily = DailyChallengeStore(ctx, "arrows_test_achievements2")
        val manager = ArrowsAchievementsManager(ctx, json, ds, daily)
        manager.checkExistingAchievements()
        // Poll for async unlock of first_board
        val deadline = System.currentTimeMillis() + 10_000
        var unlocked = false
        while (System.currentTimeMillis() < deadline) {
            val statuses = manager.getAchievementStatuses().first()
            if (statuses.any { it.achievement.id == "first_board" && it.isUnlocked }) {
                unlocked = true; break
            }
            Thread.sleep(50)
        }
        assertTrue(unlocked, "first_board should be back-filled after a cleared board")
        Unit
    }
}
