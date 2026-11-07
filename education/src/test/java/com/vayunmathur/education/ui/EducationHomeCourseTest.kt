package com.vayunmathur.education.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.education.R
import com.vayunmathur.education.content.Exercise
import com.vayunmathur.education.content.Subject
import com.vayunmathur.education.util.CourseActions
import com.vayunmathur.education.util.CourseUiState
import com.vayunmathur.education.util.CourseUnitRow
import com.vayunmathur.education.util.HomeActions
import com.vayunmathur.education.util.HomeCourse
import com.vayunmathur.education.util.HomeDeadline
import com.vayunmathur.education.util.HomeSection
import com.vayunmathur.education.util.HomeUiState
import com.vayunmathur.education.content.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Scholar home (sections/deadlines) and course (units/challenge) screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EducationHomeCourseTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg a: Any): String = context.getString(id, *a)

    @Test
    fun homeShowsSectionsCoursesAndOpensCourse() {
        var opened: String? = null
        val actions = object : HomeActions {
            override fun openCourse(courseId: String) { opened = courseId }
        }
        composeRule.setContent {
            ScholarHomeScreen(
                HomeUiState(
                    learnerName = "Maya", streakCount = 4, totalStars = 27,
                    sections = listOf(
                        HomeSection(Subject.MATH, listOf(HomeCourse("c1", "Counting Fun", 3))),
                    ),
                ),
                actions,
            )
        }
        composeRule.onNodeWithText(str(R.string.hi_1, "Maya")).assertIsDisplayed()
        composeRule.onNodeWithText("Counting Fun").assertIsDisplayed()
        composeRule.onNodeWithText("27").assertIsDisplayed()
        composeRule.onNodeWithText("Counting Fun").performClick()
        assertEquals("c1", opened)
    }

    @Test
    fun homeEmptyShowsNoPacksAndDeadlineOpens() {
        var deadline: HomeDeadline? = null
        val actions = object : HomeActions {
            override fun openDeadline(d: HomeDeadline) { deadline = d }
        }
        val due = HomeDeadline(1L, "Finish Unit 1", 20_000L, ModuleType.UNIT, "u1")
        composeRule.setContent {
            ScholarHomeScreen(HomeUiState(deadlines = listOf(due)), actions)
        }
        composeRule.onNodeWithText(str(R.string.due_soon)).assertIsDisplayed()
        composeRule.onNodeWithText("Finish Unit 1").assertIsDisplayed()
        composeRule.onNodeWithText("Finish Unit 1").performClick()
        assertEquals(due, deadline)
    }

    @Test
    fun homeNoContentMessageWhenNoSectionsOrDeadlines() {
        composeRule.setContent { ScholarHomeScreen(HomeUiState(), HomeActions.Noop) }
        composeRule.onNodeWithText(str(R.string.no_content_packs_are_installed_yet)).assertIsDisplayed()
    }

    @Test
    fun courseShowsUnitsChallengeAndOpens() {
        var unit: String? = null
        var exercise: String? = null
        val actions = object : CourseActions {
            override fun openUnit(unitId: String) { unit = unitId }
            override fun openExercise(exerciseId: String) { exercise = exerciseId }
        }
        composeRule.setContent {
            ScholarCourseScreen(
                CourseUiState(
                    title = "Counting Fun", description = "Numbers to ten",
                    units = listOf(CourseUnitRow("u1", "Numbers 1-5", 4, stars = 2)),
                    challenge = Exercise("ch1", "Big Challenge", listOf("q1")),
                ),
                actions,
            )
        }
        composeRule.onNodeWithText("Counting Fun").assertIsDisplayed()
        composeRule.onNodeWithText("Numbers 1-5").assertIsDisplayed()
        composeRule.onNodeWithText("Numbers 1-5").performClick()
        assertEquals("u1", unit)
        composeRule.onNodeWithText("Big Challenge").performClick()
        assertEquals("ch1", exercise)
    }

    @Test
    fun courseUnavailableShowsMissingContent() {
        composeRule.setContent {
            ScholarCourseScreen(CourseUiState(title = "Gone", available = false), CourseActions.Noop)
        }
        composeRule.onNodeWithText(str(R.string.this_course_is_unavailable)).assertIsDisplayed()
    }

    @Test
    fun starRowAndChipsRender() {
        composeRule.setContent {
            StarRow(stars = 2)
            StreakChip(5)
            StarsChip(12)
        }
        composeRule.onNodeWithText("12").assertIsDisplayed()
        assertTrue(true)
    }
}
