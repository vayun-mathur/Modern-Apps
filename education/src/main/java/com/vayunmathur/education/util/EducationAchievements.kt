package com.vayunmathur.education.util

import android.content.Context
import com.vayunmathur.library.util.AchievementsManager

/**
 * Education badges/stickers, backed by the shared DataStore [AchievementsManager].
 * Award calls are driven from [EducationViewModel.commitResult]; earned badges
 * surface as an [com.vayunmathur.library.ui.AchievementNotification] and in the
 * badges gallery.
 */
class EducationAchievements(context: Context) : AchievementsManager(context, BADGES_JSON) {

    override fun checkExistingAchievements() {
        // Awards are (re)evaluated on each committed quiz result; nothing to
        // backfill here.
    }

    object Ids {
        const val FIRST_STAR = "first_star"
        const val PERFECT = "perfect_quiz"
        const val STREAK_3 = "streak_3"
        const val STREAK_7 = "streak_7"
        const val STARS_10 = "stars_10"
        const val STARS_25 = "stars_25"
        const val FIVE_SKILLS = "five_skills"
        const val COURSE_MASTER = "course_master"
    }

    companion object {
        /** Emoji sticker per badge id, for the kid-facing gallery. */
        val STICKERS: Map<String, String> = mapOf(
            Ids.FIRST_STAR to "⭐",
            Ids.PERFECT to "💯",
            Ids.STREAK_3 to "🔥",
            Ids.STREAK_7 to "🏆",
            Ids.STARS_10 to "🌟",
            Ids.STARS_25 to "🎖️",
            Ids.FIVE_SKILLS to "🧠",
            Ids.COURSE_MASTER to "🎓",
        )

        private val BADGES_JSON = """
        [
          {"id":"first_star","name":"First Star","description":"Earn your very first star."},
          {"id":"perfect_quiz","name":"Perfect!","description":"Get 3 stars on a quiz."},
          {"id":"streak_3","name":"On a Roll","description":"Learn 3 days in a row.","targetProgress":3},
          {"id":"streak_7","name":"Week Warrior","description":"Learn 7 days in a row.","targetProgress":7},
          {"id":"stars_10","name":"Star Collector","description":"Collect 10 stars.","targetProgress":10},
          {"id":"stars_25","name":"Star Champion","description":"Collect 25 stars.","targetProgress":25},
          {"id":"five_skills","name":"Quick Learner","description":"Earn stars in 5 different skills.","targetProgress":5},
          {"id":"course_master","name":"Course Master","description":"Master every skill in a course."}
        ]
        """.trimIndent()
    }
}
