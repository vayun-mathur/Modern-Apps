package com.vayunmathur.games.hub.util

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * XP/Level system mirroring Play Games.
 * Formula: level = floor(sqrt(totalXp / 100)) + 1
 * L1=0xp, L2=100, L3=400, L4=900, L5=1600...
 * 7 games * ~7 achievements avg 25xp ~=1225 XP => L4.
 */
object XpLevelCalculator {

    fun level(totalXp: Int): Int {
        if (totalXp <= 0) return 1
        return floor(sqrt(totalXp.toDouble() / 100.0)).toInt() + 1
    }

    fun xpForLevel(level: Int): Int {
        if (level <= 1) return 0
        val l = level - 1
        return l * l * 100
    }

    fun xpToNextLevel(totalXp: Int): Int {
        val currentLevel = level(totalXp)
        val nextLevelXp = xpForLevel(currentLevel + 1)
        return nextLevelXp - totalXp
    }

    fun progressToNextLevel(totalXp: Int): Float {
        val currentLevel = level(totalXp)
        val currentLevelXp = xpForLevel(currentLevel)
        val nextLevelXp = xpForLevel(currentLevel + 1)
        val range = (nextLevelXp - currentLevelXp).toFloat()
        if (range <= 0f) return 1f
        return ((totalXp - currentLevelXp).toFloat() / range).coerceIn(0f, 1f)
    }

    fun title(level: Int): String = when {
        level >= 25 -> "Legend"
        level >= 18 -> "Grandmaster"
        level >= 12 -> "Master"
        level >= 8 -> "Enthusiast"
        level >= 4 -> "Casual Gamer"
        level >= 2 -> "Novice"
        else -> "Beginner"
    }
}
