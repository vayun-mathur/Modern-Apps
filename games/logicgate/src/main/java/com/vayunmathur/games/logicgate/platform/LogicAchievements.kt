package com.vayunmathur.games.logicgate.platform

import android.content.Context
import com.vayunmathur.games.logicgate.data.ProgressStore
import com.vayunmathur.games.logicgate.domain.levels.Chapter
import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.sim.Scoring
import com.vayunmathur.library.util.AchievementsManager

/** Achievements derived entirely from the stored best costs, so re-checking is always safe. */
class LogicAchievements(
    context: Context,
    json: String,
    private val progress: ProgressStore,
) : AchievementsManager(context, json) {

    override fun checkExistingAchievements() {
        val best = progress.bestCosts()
        if (best.isEmpty()) return
        onAchievementUnlocked("lg2_first_circuit")
        for ((chapter, id) in chapterAchievements) {
            onProgressUpdated(id, LevelRegistry.chapter(chapter).count { it.id in best })
        }
        if ("computer" in best) onAchievementUnlocked("lg2_computer")
        val threeStars = LevelRegistry.levels.count { level ->
            best[level.id]?.let { Scoring.stars(solved = true, cost = it, par = level.par) } == MAX_STARS
        }
        onProgressUpdated("lg2_three_stars_10", threeStars)
    }

    private companion object {
        const val MAX_STARS = 3
        val chapterAchievements = mapOf(
            Chapter.BASICS to "lg2_basics",
            Chapter.ROUTING to "lg2_routing",
            Chapter.ARITHMETIC to "lg2_arith",
            Chapter.MEMORY to "lg2_memory",
        )
    }
}
