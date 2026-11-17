package com.vayunmathur.games.unblockjam.platform

import android.content.Context
import com.vayunmathur.games.unblockjam.data.CompletedLevelsRepository
import com.vayunmathur.games.unblockjam.data.LevelPack
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DailyChallengeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Unlocks Unblock Jam achievements from level stats, moves, undos, and daily streaks. */
class UnblockJamAchievementsManager(
    context: Context,
    json: String,
    private val repository: CompletedLevelsRepository
) : AchievementsManager(context, json) {

    private val dailyStore = DailyChallengeStore(context, "unblockjam_daily")

    override fun checkExistingAchievements() {
        val stats = repository.getLevelStats()
        if (stats.isNotEmpty()) onAchievementUnlocked("first_level")
        onProgressUpdated("level_50", stats.size)
        onProgressUpdated("moves_1000", repository.getTotalMoves())
        onProgressUpdated("undo_master", repository.getUndoCount())
        onPackCompletionChanged(stats.keys)

        CoroutineScope(Dispatchers.IO).launch {
            val bestStreak = dailyStore.bestStreak()
            if (bestStreak > 0) {
                onProgressUpdated("daily_streak_7", bestStreak.toInt())
                onProgressUpdated("daily_streak_30", bestStreak.toInt())
            }
        }
    }

    /** Re-derives the per-pack completion achievements from the set of solved level ids. */
    fun onPackCompletionChanged(completed: Set<String>) {
        onProgressUpdated("all_levels_pack_0", completedIn(completed, listOf(0)))
        onProgressUpdated("all_wall_packs", completedIn(completed, LevelPack.WALL_PACK_INDICES))
        onProgressUpdated("all_size_packs", completedIn(completed, LevelPack.SIZE_PACK_INDICES))
    }

    // One stats map covers every shipped pack, so its raw size would let one pack's progress
    // unlock another pack's achievement.
    private fun completedIn(completed: Set<String>, packs: List<Int>): Int =
        packs.sumOf { index ->
            LevelPack.PACKS[index].levels.count { completed.contains(it.id) }
        }
}
