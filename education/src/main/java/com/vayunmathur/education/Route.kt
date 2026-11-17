package com.vayunmathur.education

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route
    @Serializable
    data class Course(val courseId: String) : Route
    @Serializable
    data class UnitScreen(val unitId: String) : Route
    @Serializable
    data class LessonScreen(val lessonId: String) : Route
    @Serializable
    data class Quiz(val exerciseId: String) : Route
    @Serializable
    data class VideoPlayer(val videoUrl: String, val title: String) : Route
    @Serializable
    data class K2Lesson(val lessonId: String) : Route
    @Serializable
    data class K2Quiz(val exerciseId: String) : Route
    @Serializable
    data class K2Reward(val stars: Int) : Route
    @Serializable
    data class Results(val total: Int, val correct: Int, val stars: Int) : Route
    @Serializable
    data object ParentGate : Route
    @Serializable
    data object Parent : Route
    @Serializable
    data object Badges : Route
}
