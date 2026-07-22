package com.vayunmathur.games.logicgate.data

import android.content.Context
import com.vayunmathur.library.util.LevelStatsRepository

class LogicProgressRepository(context: Context) : LevelStatsRepository(context, "logicgate_stats") {
    fun getCompletedLevelIds(): Set<String> = getLevelStats().keys

    fun isLevelComplete(id: String): Boolean = getLevelStats().containsKey(id)

    fun bestNandCount(levelId: String): Int? = getLevelStats()[levelId]?.bestScore

    // we reuse bestScore as nand count (lower is better, but LevelStatsRepository keeps min)
    // so storing nand count directly works

    fun unlockedChipIds(): Set<String> {
        val completed = getCompletedLevelIds()
        val chips = mutableSetOf("NAND")
        for (lvlId in completed) {
            val lvl = Levels.byId[lvlId] ?: continue
            lvl.unlocksChipId?.let { chips.add(it) }
        }
        return chips
    }

    fun totalCompleted(): Int = getLevelStats().size

    fun incCircuitsChecked() = incrementCounter("circuits_checked")
    fun getCircuitsChecked(): Int = getCounter("circuits_checked")

    fun setChipHintUsed(chipId: String) = incrementCounter("hint_$chipId")
}
