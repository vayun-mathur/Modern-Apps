package com.vayunmathur.games.nonogram.platform

import android.content.Context
import com.vayunmathur.games.nonogram.data.NonogramDataStore
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DailyChallengeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Reports Nonogram achievements and progress to the shared games hub. */
class NonogramAchievementsManager(
    context: Context,
    json: String,
    private val dataStore: NonogramDataStore,
    private val dailyStore: DailyChallengeStore,
) : AchievementsManager(context, json) {

    /**
     * Back-fills from stored progress on launch, so an achievement added in a later release is awarded
     * for work the player has already done rather than only for the next puzzle they finish.
     *
     * The reads are suspending, so this hands off to a coroutine rather than blocking. Anything about
     * how a single puzzle was played — whether a wrong cell was ever filled — is gone once the level
     * advances, so those are unlocked from the win handler instead.
     */
    override fun checkExistingAchievements() {
        CoroutineScope(Dispatchers.IO).launch {
            val total = dataStore.puzzlesCompleted.first()
            if (total > 0) onAchievementUnlocked("first_puzzle")
            onProgressUpdated("puzzles_10", total)
            onProgressUpdated("puzzles_50", total)

            if (dailyStore.lastCompletedDay() != DailyChallengeStore.NO_DAY) {
                onAchievementUnlocked("first_daily")
            }
            val best = dailyStore.bestStreak().toInt()
            onProgressUpdated("daily_streak_7", best)
            onProgressUpdated("daily_streak_30", best)
        }
    }
}
