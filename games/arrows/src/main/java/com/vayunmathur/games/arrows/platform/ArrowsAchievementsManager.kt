package com.vayunmathur.games.arrows.platform

import android.content.Context
import com.vayunmathur.games.arrows.data.ArrowsDataStore
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DailyChallengeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Reports Arrows achievements and progress to the shared games hub. */
class ArrowsAchievementsManager(
    context: Context,
    json: String,
    private val dataStore: ArrowsDataStore,
    private val dailyStore: DailyChallengeStore,
) : AchievementsManager(context, json) {

    /**
     * Back-fills from stored progress on launch, so an achievement added in a later release is awarded
     * for work the player has already done rather than only for the next board they clear.
     *
     * The reads are suspending, so this hands off to a coroutine rather than blocking. Anything about
     * how a single board was played — whether a heart was lost, whether it had mirrors — is gone once
     * the level advances, so those are unlocked from the clear handler instead.
     */
    override fun checkExistingAchievements() {
        CoroutineScope(Dispatchers.IO).launch {
            val total = dataStore.boardsCleared.first()
            if (total > 0) onAchievementUnlocked("first_board")
            onProgressUpdated("boards_10", total)
            onProgressUpdated("boards_50", total)

            if (dailyStore.lastCompletedDay() != DailyChallengeStore.NO_DAY) {
                onAchievementUnlocked("first_daily")
            }
            val best = dailyStore.bestStreak().toInt()
            onProgressUpdated("daily_streak_7", best)
            onProgressUpdated("daily_streak_30", best)
        }
    }
}
