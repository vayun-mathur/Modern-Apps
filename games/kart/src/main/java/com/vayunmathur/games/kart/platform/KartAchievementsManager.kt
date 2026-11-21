package com.vayunmathur.games.kart.platform

import android.content.Context
import com.vayunmathur.games.kart.data.KartDataStore
import com.vayunmathur.library.util.AchievementsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Reports Kart achievements and progress to the shared games hub. */
class KartAchievementsManager(
    context: Context,
    json: String,
    private val dataStore: KartDataStore,
) : AchievementsManager(context, json) {

    /**
     * Back-fills from stored progress on launch, so an achievement added in a later release is awarded
     * for work the player has already done rather than only for the next race they finish.
     *
     * The reads are flows, so this hands off to a coroutine rather than blocking. Finishing a race is
     * what banks the next cup (see KartViewModel.finishRace), so a cup pointer past the first cup —
     * or a recorded win — proves a race was finished, and a win proves [first_win]. Whether the final
     * cup was completed cannot be told from the pointer alone (it stays on the last cup), so
     * `cup_complete` is only unlocked live from the finish handler.
     */
    override fun checkExistingAchievements() {
        CoroutineScope(Dispatchers.IO).launch {
            val racesWon = dataStore.racesWon.first()
            val currentCup = dataStore.currentCup.first()
            if (racesWon > 0 || currentCup > 0) onAchievementUnlocked("first_race")
            if (racesWon > 0) onAchievementUnlocked("first_win")
            if (currentCup > 0) onAchievementUnlocked("cup_complete")
        }
    }
}
