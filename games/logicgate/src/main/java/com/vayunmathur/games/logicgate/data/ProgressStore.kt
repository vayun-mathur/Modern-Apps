package com.vayunmathur.games.logicgate.data

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.LevelStatsRepository

/**
 * Which levels are solved and at what best NAND cost (lower wins), plus which level briefs the
 * player has already seen. A level is complete exactly when it has a best cost.
 */
class ProgressStore(context: Context) {
    private val stats = LevelStatsRepository(context, PREFS_NAME)
    private val ds = DataStoreUtils.getInstance(context)

    fun bestCosts(): Map<String, Int> = stats.getLevelStats().mapValues { it.value.bestScore }

    fun record(levelId: String, cost: Int) = stats.updateBestScore(levelId, cost)

    /** Marks the brief for [levelId] seen; true when this is the first visit. */
    suspend fun markBriefSeen(levelId: String): Boolean = ds.addStringToSetIfAbsent(BRIEFS_KEY, levelId)

    /** Storage names. */
    companion object {
        const val PREFS_NAME = "logicgate2_stats"
        private const val BRIEFS_KEY = "logicgate2_briefs_seen"
    }
}
