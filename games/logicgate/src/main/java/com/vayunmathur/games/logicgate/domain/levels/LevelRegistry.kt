package com.vayunmathur.games.logicgate.domain.levels

import com.vayunmathur.games.logicgate.domain.model.Chips

/** The ordered level list and the rules for what is open and what is in the chip tray. */
object LevelRegistry {
    val levels: List<LevelDef> = basicsLevels + routingLevels + arithmeticLevels + memoryLevels + cpuLevels

    private val byId = levels.associateBy { it.id }

    operator fun get(id: String): LevelDef? = byId[id]

    fun indexOf(id: String): Int = levels.indexOfFirst { it.id == id }

    fun next(id: String): LevelDef? = levels.getOrNull(indexOf(id) + 1)

    fun chapter(chapter: Chapter): List<LevelDef> = levels.filter { it.chapter == chapter }

    /**
     * The first level is always open. Otherwise a level opens once the previous level of its
     * chapter is solved; a chapter's first level needs the previous chapter's last level.
     */
    fun isUnlocked(id: String, completed: Set<String>): Boolean {
        val i = indexOf(id)
        return i == 0 || (i > 0 && levels[i - 1].id in completed)
    }

    /**
     * Chips on offer in level [id], newest first: what this and earlier levels gift, everything
     * earned before it, then NAND and the constants.
     */
    fun allowedChips(id: String): List<String> {
        val idx = indexOf(id)
        if (idx < 0) return Chips.base
        val chips = LinkedHashSet<String>()
        for (i in idx downTo 0) {
            if (i < idx) chips += levels[i].unlocks
            chips += levels[i].gifts
        }
        chips += Chips.base
        return chips.toList()
    }
}
