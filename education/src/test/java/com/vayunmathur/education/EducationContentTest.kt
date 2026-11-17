package com.vayunmathur.education

import com.vayunmathur.education.domain.ChoiceAnswer
import com.vayunmathur.education.domain.ContentPack
import com.vayunmathur.education.domain.ContentValidator
import com.vayunmathur.education.domain.MatchingAnswer
import com.vayunmathur.education.domain.MatchingQuestion
import com.vayunmathur.education.domain.MultiChoiceAnswer
import com.vayunmathur.education.domain.MultipleChoiceQuestion
import com.vayunmathur.education.domain.MultipleSelectQuestion
import com.vayunmathur.education.domain.NumericAnswer
import com.vayunmathur.education.domain.NumericQuestion
import com.vayunmathur.education.domain.OrderingAnswer
import com.vayunmathur.education.domain.OrderingQuestion
import com.vayunmathur.education.domain.Prompt
import com.vayunmathur.education.domain.ShortTextQuestion
import com.vayunmathur.education.domain.TraceAnswer
import com.vayunmathur.education.domain.TracingQuestion
import com.vayunmathur.education.domain.isCorrect
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

class EducationContentTest {

    private fun allPacks(): List<Pair<String, ContentPack>> {
        val dir = File("src/main/assets/content")
        assertTrue(dir.isDirectory, "content dir missing at ${dir.absolutePath}")
        val files = dir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name } ?: emptyList()
        assertTrue(files.isNotEmpty(), "expected at least one content pack")
        return files.map { it.name to json.decodeFromString<ContentPack>(it.readText()) }
    }

    @Test
    fun allPacks_parseAndValidate() {
        allPacks().forEach { (name, pack) ->
            val errors = ContentValidator.validate(pack)
            assertTrue(errors.isEmpty(), "$name has validation errors: $errors")
        }
    }

    @Test
    fun allPacks_exerciseRefsResolve() {
        allPacks().forEach { (name, pack) ->
            val questionIds = pack.questions.map { it.id }.toSet()
            val referenced = buildList {
                pack.courses.forEach { c ->
                    c.challenge?.let { addAll(it.questionIds) }
                    c.units.forEach { u ->
                        u.quiz?.let { addAll(it.questionIds) }
                        u.lessons.forEach { l -> l.exercise?.let { addAll(it.questionIds) } }
                    }
                }
            }
            assertTrue(questionIds.containsAll(referenced), "$name: all referenced questions must exist")
        }
    }

    @Test
    fun packs_haveNoCrossPackIdCollisions() {
        val packs = allPacks().map { it.second }
        fun <T> dupes(items: List<T>) = items.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertTrue(dupes(packs.flatMap { it.courses.map { c -> c.id } }).isEmpty(), "duplicate course ids")
        assertTrue(dupes(packs.flatMap { it.skills.map { s -> s.id } }).isEmpty(), "duplicate skill ids")
        assertTrue(dupes(packs.flatMap { it.questions.map { q -> q.id } }).isEmpty(), "duplicate question ids")
    }

    @Test
    fun validator_flagsUnknownSkillReference() {
        val pack = ContentPack(
            id = "bad",
            name = "bad",
            questions = listOf(
                MultipleChoiceQuestion(
                    id = "q1",
                    skillId = "does-not-exist",
                    prompt = Prompt("2 + 2?"),
                    choices = listOf(
                        com.vayunmathur.education.domain.Choice("4"),
                        com.vayunmathur.education.domain.Choice("5"),
                    ),
                    correctIndex = 0,
                ),
            ),
        )
        val errors = ContentValidator.validate(pack)
        assertTrue(errors.any { it.contains("unknown skill") })
    }

    @Test
    fun grading_multipleChoice() {
        val q = MultipleChoiceQuestion(
            id = "q", skillId = "s", prompt = Prompt("?"),
            choices = listOf(
                com.vayunmathur.education.domain.Choice("a"),
                com.vayunmathur.education.domain.Choice("b"),
            ),
            correctIndex = 1,
        )
        assertTrue(q.isCorrect(ChoiceAnswer(1)))
        assertFalse(q.isCorrect(ChoiceAnswer(0)))
        assertFalse(q.isCorrect(null))
    }

    @Test
    fun grading_multipleSelect_orderIndependent() {
        val q = MultipleSelectQuestion(
            id = "q", skillId = "s", prompt = Prompt("?"),
            choices = listOf(
                com.vayunmathur.education.domain.Choice("a"),
                com.vayunmathur.education.domain.Choice("b"),
                com.vayunmathur.education.domain.Choice("c"),
            ),
            correctIndices = listOf(0, 2),
        )
        assertTrue(q.isCorrect(MultiChoiceAnswer(setOf(2, 0))))
        assertFalse(q.isCorrect(MultiChoiceAnswer(setOf(0))))
        assertFalse(q.isCorrect(MultiChoiceAnswer(setOf(0, 1, 2))))
    }

    @Test
    fun grading_numeric_withTolerance() {
        val q = NumericQuestion(id = "q", skillId = "s", prompt = Prompt("?"), answer = 5.0, tolerance = 0.1)
        assertTrue(q.isCorrect(NumericAnswer(5.05)))
        assertFalse(q.isCorrect(NumericAnswer(5.2)))
    }

    @Test
    fun grading_ordering_matchesCanonicalOrder() {
        val q = OrderingQuestion(id = "q", skillId = "s", prompt = Prompt("?"), items = listOf("a", "b", "c"))
        assertTrue(q.isCorrect(OrderingAnswer(listOf(0, 1, 2))))
        assertFalse(q.isCorrect(OrderingAnswer(listOf(1, 0, 2))))
    }

    @Test
    fun grading_matching() {
        val q = MatchingQuestion(
            id = "q", skillId = "s", prompt = Prompt("?"),
            left = listOf("x", "y"), right = listOf("1", "2"),
            correctRightForLeft = listOf(1, 0),
        )
        assertTrue(q.isCorrect(MatchingAnswer(listOf(1, 0))))
        assertFalse(q.isCorrect(MatchingAnswer(listOf(0, 1))))
    }

    @Test
    fun grading_tracing_acceptsCompletion() {
        val q = TracingQuestion(id = "q", skillId = "s", prompt = Prompt("?"), glyph = "1")
        assertTrue(q.isCorrect(TraceAnswer))
        assertFalse(q.isCorrect(null))
    }

    @Test
    fun starsFor_ratios() {
        assertEquals(3, com.vayunmathur.education.platform.EducationViewModel.starsFor(4, 4))
        assertEquals(2, com.vayunmathur.education.platform.EducationViewModel.starsFor(3, 4))
        assertEquals(1, com.vayunmathur.education.platform.EducationViewModel.starsFor(2, 4))
        assertEquals(0, com.vayunmathur.education.platform.EducationViewModel.starsFor(0, 4))
    }

    @Test
    fun nextStreak_transitions() {
        assertEquals(1, com.vayunmathur.education.platform.EducationViewModel.nextStreak(0, 0, 100))
        assertEquals(6, com.vayunmathur.education.platform.EducationViewModel.nextStreak(5, 99, 100))
        assertEquals(5, com.vayunmathur.education.platform.EducationViewModel.nextStreak(5, 100, 100))
        assertEquals(1, com.vayunmathur.education.platform.EducationViewModel.nextStreak(5, 90, 100))
    }
}
