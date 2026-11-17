package com.vayunmathur.education.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * The single learner for this install. There is exactly one row, pinned to
 * [SINGLE_ID]; the app opens straight into this learner's band home.
 *
 * @param gradeLevel nominal grade (0 == Kindergarten). Drives the band unless
 *   [bandOverride] is set.
 * @param bandOverride optional [com.vayunmathur.education.domain.Band] name to
 *   force a shell regardless of grade (precocious / catching-up learners). Set
 *   from parent mode.
 * @param pinHash SHA-256 (hex) of the parent PIN, or null before it is set.
 * @param streakCount consecutive days with >= 1 completed activity.
 * @param lastActivityEpochDay epoch-day of the last completed activity (0 == none).
 */
@Serializable
@Entity
data class Learner(
    @PrimaryKey val id: Long = SINGLE_ID,
    val name: String = "",
    val avatar: String = "🦉",
    val gradeLevel: Int = 0,
    val bandOverride: String? = null,
    val pinHash: String? = null,
    val dailyGoal: Int = 1,
    val streakCount: Int = 0,
    val lastActivityEpochDay: Long = 0,
    val totalStars: Int = 0,
    val onboarded: Boolean = false,
) {
    /** Constants for the single-learner table, including its pinned row id. */
    companion object {
        const val SINGLE_ID = 1L
    }
}
