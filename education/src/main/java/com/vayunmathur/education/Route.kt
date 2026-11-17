package com.vayunmathur.education

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * Navigation destinations for the Education app, from the catalog home through lessons, quizzes, results, and
 * parent screens.
 */
@Serializable
sealed interface Route : NavKey {
    /** Catalog home destination showing courses, streak, stars, and deadlines. */
    @Serializable
    data object Home : Route
    /** Course detail destination for the course identified by [courseId]. */
    @Serializable
    data class Course(val courseId: String) : Route
    /** Unit detail destination for the unit identified by [unitId]. */
    @Serializable
    data class UnitScreen(val unitId: String) : Route
    /** Scholar lesson destination for the lesson identified by [lessonId]. */
    @Serializable
    data class LessonScreen(val lessonId: String) : Route
    /** Scholar quiz destination for the exercise identified by [exerciseId]. */
    @Serializable
    data class Quiz(val exerciseId: String) : Route
    /** Streaming video lesson destination for [videoUrl], titled [title]. */
    @Serializable
    data class VideoPlayer(val videoUrl: String, val title: String) : Route
    /** K-2 lesson destination for the lesson identified by [lessonId]. */
    @Serializable
    data class K2Lesson(val lessonId: String) : Route
    /** K-2 quiz destination for the exercise identified by [exerciseId]. */
    @Serializable
    data class K2Quiz(val exerciseId: String) : Route
    /** K-2 reward destination celebrating [stars] earned stars. */
    @Serializable
    data class K2Reward(val stars: Int) : Route
    /** Quiz results destination summarising [correct] of [total] answers and [stars] earned. */
    @Serializable
    data class Results(val total: Int, val correct: Int, val stars: Int) : Route
    /** PIN gate destination guarding entry to the parent area. */
    @Serializable
    data object ParentGate : Route
    /** Parent area destination for learner settings, deadlines, and progress controls. */
    @Serializable
    data object Parent : Route
    /** Badge gallery destination showing earned and locked achievements. */
    @Serializable
    data object Badges : Route
}
