package com.vayunmathur.library.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Unlocks that land together are shown one after another, oldest first, and none is dropped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AchievementsQueueTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private class Achievements(context: Context, prefix: String) : AchievementsManager(
        context,
        """[{"id":"first_win","name":"First Win","description":"win once","targetProgress":1},
            {"id":"win_10","name":"Ten","description":"win ten","targetProgress":10},
            {"id":"win_50","name":"Fifty","description":"win fifty","targetProgress":50}]""",
        prefix,
    ) {
        override fun checkExistingAchievements() {}
    }

    private suspend fun await(cond: suspend () -> Boolean) {
        withTimeout(5_000) { while (!cond()) delay(20) }
    }

    private suspend fun Achievements.isStored(id: String) =
        getAchievementStatuses().first().first { it.achievement.id == id }.isUnlocked

    @Test fun twoQueuedUnlocksShowInOrder() = runBlocking {
        val mgr = Achievements(ctx, "queue${System.nanoTime()}_")
        mgr.onAchievementUnlocked("first_win")
        await { mgr.newAchievement.value?.id == "first_win" }
        mgr.onAchievementUnlocked("win_10")
        // Wait until the second unlock has really landed, then check it did not replace the first.
        await { mgr.isStored("win_10") }
        delay(50)
        assertEquals("first_win", mgr.newAchievement.value?.id)

        mgr.dismissNotification()
        assertEquals("win_10", mgr.newAchievement.value?.id)
        mgr.dismissNotification()
        assertNull(mgr.newAchievement.value)
    }

    @Test fun aBurstOfUnlocksLosesNone() = runBlocking {
        val mgr = Achievements(ctx, "burst${System.nanoTime()}_")
        listOf("first_win", "win_10", "win_50").forEach(mgr::onAchievementUnlocked)
        await { listOf("first_win", "win_10", "win_50").all { mgr.isStored(it) } }
        delay(50)
        val shown = mutableListOf<String>()
        while (true) {
            val current = mgr.newAchievement.value ?: break
            shown += current.id
            mgr.dismissNotification()
        }
        assertEquals(setOf("first_win", "win_10", "win_50"), shown.toSet())
        assertEquals(3, shown.size)
    }

    @Test fun dismissingWithNothingQueuedIsHarmless() {
        val mgr = Achievements(ctx, "empty${System.nanoTime()}_")
        mgr.dismissNotification()
        mgr.dismissNotification()
        assertNull(mgr.newAchievement.value)
    }
}
