package com.vayunmathur.library.util

import kotlinx.serialization.Serializable

/** A game achievement definition: id, display text, target progress and secrecy. */
@Serializable
data class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val targetProgress: Int = 1,
    val isSecret: Boolean = false
)

/** A player's current progress against one [Achievement]. */
data class AchievementStatus(
    val achievement: Achievement,
    val progress: Int,
    val isUnlocked: Boolean
)
