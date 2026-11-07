package com.vayunmathur.education.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ContentValidator rules, Grades banding and home/course screens' data helpers. */
class EducationContentValidatorTest {

    private val skill = Skill("s1", Subject.MATH, "Counting")
    private val mc = MultipleChoiceQuestion(
        id = "q1", skillId = "s1", prompt = Prompt("1+1?"),
        choices = listOf(Choice("1"), Choice("2")), correctIndex = 1,
    )

    private fun pack(
        skills: List<Skill> = listOf(skill),
        questions: List<Question> = listOf(mc),
        courses: List<Course> = emptyList(),
    ) = ContentPack(id = "p1", name = "Pack", skills = skills, questions = questions, courses = courses)

    @Test
    fun validPackHasNoErrors() {
        assertEquals(emptyList(), ContentValidator.validate(pack()))
    }

    @Test
    fun duplicateIdsReported() {
        val errors = ContentValidator.validate(
            pack(skills = listOf(skill, skill), questions = listOf(mc, mc)),
        )
        assertTrue("Duplicate skill id: s1" in errors)
        assertTrue("Duplicate question id: q1" in errors)
    }

    @Test
    fun unknownSkillAndEmptyPromptReported() {
        val orphan = mc.copy(id = "q9", skillId = "nope")
        val blank = mc.copy(id = "q8", prompt = Prompt(""))
        val errors = ContentValidator.validate(pack(questions = listOf(orphan, blank)))
        assertTrue(errors.any { it.contains("unknown skill 'nope'") })
        assertTrue(errors.any { it.contains("empty prompt") })
    }

    @Test
    fun perTypeFieldChecks() {
        val badMc = mc.copy(id = "b1", choices = listOf(Choice("x")), correctIndex = 5)
        val badMs = MultipleSelectQuestion(
            id = "b2", skillId = "s1", prompt = Prompt("p"),
            choices = listOf(Choice("a")), correctIndices = emptyList(),
        )
        val badNumeric = NumericQuestion(id = "b3", skillId = "s1", prompt = Prompt("p"), answer = 1.0, tolerance = -1.0)
        val badText = ShortTextQuestion(id = "b4", skillId = "s1", prompt = Prompt("p"), acceptedAnswers = emptyList())
        val badOrdering = OrderingQuestion(id = "b5", skillId = "s1", prompt = Prompt("p"), items = listOf("only"))
        val badMatching = MatchingQuestion(
            id = "b6", skillId = "s1", prompt = Prompt("p"),
            left = listOf("a", "b"), right = listOf("x"), correctRightForLeft = listOf(0),
        )
        val badTracing = TracingQuestion(id = "b7", skillId = "s1", prompt = Prompt("p"), glyph = " ")
        val errors = ContentValidator.validate(
            pack(questions = listOf(badMc, badMs, badNumeric, badText, badOrdering, badMatching, badTracing)),
        )
        assertTrue(errors.any { it.contains("MC 'b1' needs >= 2 choices") })
        assertTrue(errors.any { it.contains("MC 'b1' correctIndex out of range") })
        assertTrue(errors.any { it.contains("MS 'b2' has no correct answers") })
        assertTrue(errors.any { it.contains("Numeric 'b3' has negative tolerance") })
        assertTrue(errors.any { it.contains("ShortText 'b4' has no accepted answers") })
        assertTrue(errors.any { it.contains("Ordering 'b5' needs >= 2 items") })
        assertTrue(errors.any { it.contains("Matching 'b6' left/answer length mismatch") })
        assertTrue(errors.any { it.contains("Tracing 'b7' has an empty glyph") })
    }

    @Test
    fun exerciseAndVideoReferencesChecked() {
        val course = Course(
            id = "c1", subject = Subject.MATH, title = "Math", gradeLevel = 2,
            units = listOf(
                CourseUnit(
                    id = "u1", title = "Unit",
                    lessons = listOf(
                        Lesson(
                            id = "l1", title = "Lesson",
                            videos = listOf(VideoRef("", "Broken")),
                            exercise = Exercise("e1", "Ex", listOf("q1", "ghost")),
                        ),
                    ),
                    quiz = Exercise("e2", "Quiz", emptyList()),
                ),
            ),
        )
        val errors = ContentValidator.validate(pack(courses = listOf(course)))
        assertTrue(errors.any { it.contains("unknown question 'ghost'") })
        assertTrue(errors.any { it.contains("Exercise 'e2' has no questions") })
        assertTrue(errors.any { it.contains("blank videoUrl") })
    }

    @Test
    fun gradesBandForGrade() {
        assertEquals(Band.K2, Grades.bandForGrade(0))
        assertEquals(Band.K2, Grades.bandForGrade(2))
        assertEquals(Band.ELEMENTARY, Grades.bandForGrade(3))
        assertEquals(Band.ELEMENTARY, Grades.bandForGrade(5))
        assertEquals(Band.SCHOLAR, Grades.bandForGrade(6))
        assertEquals(Band.SCHOLAR, Grades.bandForGrade(12))
    }
}
