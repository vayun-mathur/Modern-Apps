package com.vayunmathur.education.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.education.domain.Choice
import com.vayunmathur.education.domain.ChoiceAnswer
import com.vayunmathur.education.domain.Exercise
import com.vayunmathur.education.domain.MultipleChoiceQuestion
import com.vayunmathur.education.domain.Prompt
import com.vayunmathur.education.domain.Subject
import com.vayunmathur.education.platform.CourseActions
import com.vayunmathur.education.platform.CourseUiState
import com.vayunmathur.education.platform.CourseUnitRow
import com.vayunmathur.education.platform.HomeActions
import com.vayunmathur.education.platform.HomeCourse
import com.vayunmathur.education.platform.HomeSection
import com.vayunmathur.education.platform.HomeUiState
import com.vayunmathur.education.platform.QuizActions
import com.vayunmathur.education.platform.QuizUiState
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop/tablet window — comfortably above the 840dp expanded-width threshold. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Store listing images for `:education`, rendered from Compose previews instead of from an
 * instrumented test on a device.
 *
 * `./gradlew :education:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/education/`, where `release.sh` picks them up.
 *
 * Four things to keep in mind when editing:
 *
 *  - Order matters, and it comes from the function names. The generated PNG filenames embed
 *    the function name, so `Preview1Catalog`/`Preview2Course`/... sort into listing order.
 *    Renumber the functions if you reorder the listing.
 *  - Everything must be a literal. No content pack is loaded and no database exists here, so
 *    the state below is the whole input — which is also what makes the output reproducible
 *    from a clean checkout. Deadlines are deliberately left out: their chip is worded
 *    relative to today ("Due in 3 days"), which would make the image depend on the clock.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in Studio
 *    but is not collected as a screenshot test, and the build fails with the unhelpful "did
 *    not discover any tests".
 *  - The previews must be members of a class, not top-level functions. Top-level previews
 *    land in a synthetic `…Kt` facade that the screenshot engine silently skips.
 *
 * Rendering goes through the app's real [DynamicTheme] with `darkTheme = true`, matching the
 * `cmd uimode night yes` the old on-device generator used. Material You sources its palette
 * from the device wallpaper, which does not exist here, so these render with the fallback
 * scheme rather than a user's actual accent colour.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-catalog", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Catalog() {
        DynamicTheme(darkTheme = true) {
            ScholarHomeScreen(
                state = HomeUiState(
                    learnerName = "Ava",
                    streakCount = 12,
                    totalStars = 48,
                    sections = listOf(
                        HomeSection(
                            subject = Subject.MATH,
                            courses = listOf(
                                HomeCourse("course.k-math", "Counting and shapes", 3),
                                HomeCourse("course.grade3-math", "3rd grade math", 6),
                                HomeCourse("course.algebra-basics", "Algebra basics", 5),
                            ),
                        ),
                        HomeSection(
                            subject = Subject.SCIENCE,
                            courses = listOf(
                                HomeCourse("course.grade4-science", "4th grade science", 4),
                                HomeCourse("course.biology", "Biology", 7),
                            ),
                        ),
                        HomeSection(
                            subject = Subject.READING,
                            courses = listOf(
                                HomeCourse("course.grade3-reading", "3rd grade reading", 3),
                            ),
                        ),
                    ),
                ),
                actions = HomeActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-course", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Course() {
        DynamicTheme(darkTheme = true) {
            ScholarCourseScreen(
                state = CourseUiState(
                    title = "3rd grade math",
                    description = "Multiplication, division and fractions, plus the measurement " +
                        "skills that build on them.",
                    units = listOf(
                        CourseUnitRow("unit.multiplication", "Multiplication", lessonCount = 4, stars = 3),
                        CourseUnitRow("unit.division", "Division", lessonCount = 3, stars = 3),
                        CourseUnitRow("unit.fractions", "Fractions", lessonCount = 5, stars = 2),
                        CourseUnitRow("unit.area", "Area and perimeter", lessonCount = 3, stars = 1),
                        CourseUnitRow("unit.measurement", "Measurement and data", lessonCount = 4, stars = 0),
                        CourseUnitRow("unit.shapes", "Two-dimensional shapes", lessonCount = 2, stars = 0),
                    ),
                    challenge = Exercise(
                        id = "ex.grade3-challenge",
                        title = "Course challenge",
                        questionIds = emptyList(),
                    ),
                ),
                actions = CourseActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-quiz", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Quiz() {
        // Caught mid-attempt: the second of four questions, answered correctly and checked,
        // so the feedback card and the "Next" button are both on screen.
        val questions = listOf(
            MultipleChoiceQuestion(
                id = "q.mult.array",
                skillId = "skill.mult.intro",
                prompt = Prompt("An array has 2 rows of 5 dots. How many dots?"),
                choices = listOf(Choice("10"), Choice("7"), Choice("25")),
                correctIndex = 0,
                explanation = "2 × 5 = 10.",
            ),
            MultipleChoiceQuestion(
                id = "q.mult.groups",
                skillId = "skill.mult.intro",
                prompt = Prompt("There are 3 baskets with 4 apples each. How many apples in all?"),
                choices = listOf(Choice("7"), Choice("12"), Choice("9"), Choice("34")),
                correctIndex = 1,
                hints = listOf("3 groups of 4 means 4 + 4 + 4."),
                explanation = "3 × 4 = 12.",
            ),
            MultipleChoiceQuestion(
                id = "q.mult.6x7",
                skillId = "skill.mult.facts",
                prompt = Prompt("What is 6 × 7?"),
                choices = listOf(Choice("42"), Choice("36"), Choice("48"), Choice("13")),
                correctIndex = 0,
                explanation = "6 × 7 = 42.",
            ),
            MultipleChoiceQuestion(
                id = "q.mult.double",
                skillId = "skill.mult.facts",
                prompt = Prompt("Doubling a number is the same as multiplying it by what?"),
                choices = listOf(Choice("1"), Choice("2"), Choice("10")),
                correctIndex = 1,
                explanation = "Doubling is multiplying by 2.",
            ),
        )
        DynamicTheme(darkTheme = true) {
            QuizScreen(
                state = QuizUiState(title = "Multiplication practice", questions = questions),
                actions = QuizActions.Noop,
                initialIndex = 1,
                initialChecked = true,
                initialAnswers = mapOf("q.mult.groups" to ChoiceAnswer(1)),
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-k2-quiz", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4K2Quiz() {
        DynamicTheme(darkTheme = true) {
            K2QuizScreen(
                state = QuizUiState(
                    questions = listOf(
                        MultipleChoiceQuestion(
                            id = "q.k.count3",
                            skillId = "skill.k2.count",
                            prompt = Prompt("How many apples? 🍎🍎🍎"),
                            choices = listOf(Choice("2"), Choice("3"), Choice("4"), Choice("5")),
                            correctIndex = 1,
                            explanation = "There are three apples.",
                        ),
                    ),
                ),
                actions = QuizActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview5Expanded() {
        // Medium widths pair the catalog with the course: the units no longer cover
        // the course list, so the listing captures both side by side.
        DynamicTheme(darkTheme = true) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.45f)) {
                    ScholarHomeScreen(
                        state = HomeUiState(
                            learnerName = "Ava",
                            streakCount = 12,
                            totalStars = 48,
                            sections = listOf(
                                HomeSection(
                                    subject = Subject.MATH,
                                    courses = listOf(
                                        HomeCourse("course.grade3-math", "3rd grade math", 6),
                                        HomeCourse("course.algebra-basics", "Algebra basics", 5),
                                    ),
                                ),
                                HomeSection(
                                    subject = Subject.SCIENCE,
                                    courses = listOf(
                                        HomeCourse("course.biology", "Biology", 7),
                                    ),
                                ),
                            ),
                        ),
                        actions = HomeActions.Noop,
                    )
                }
                Box(Modifier.weight(0.55f)) {
                    ScholarCourseScreen(
                        state = CourseUiState(
                            title = "3rd grade math",
                            description = "Multiplication, division and fractions.",
                            units = listOf(
                                CourseUnitRow("unit.multiplication", "Multiplication", lessonCount = 4, stars = 3),
                                CourseUnitRow("unit.division", "Division", lessonCount = 3, stars = 3),
                                CourseUnitRow("unit.fractions", "Fractions", lessonCount = 5, stars = 2),
                            ),
                            challenge = Exercise(
                                id = "ex.grade3-challenge",
                                title = "Course challenge",
                                questionIds = emptyList(),
                            ),
                        ),
                        actions = CourseActions.Noop,
                    )
                }
            }
        }
    }
}
