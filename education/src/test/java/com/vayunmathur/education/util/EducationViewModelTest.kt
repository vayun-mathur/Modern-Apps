package com.vayunmathur.education.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.education.EducationFixtures as Fx
import com.vayunmathur.education.content.Band
import com.vayunmathur.education.content.ChoiceAnswer
import com.vayunmathur.education.content.ContentRepository
import com.vayunmathur.education.content.MatchingAnswer
import com.vayunmathur.education.content.ModuleType
import com.vayunmathur.education.content.MultiChoiceAnswer
import com.vayunmathur.education.content.NumericAnswer
import com.vayunmathur.education.content.OrderingAnswer
import com.vayunmathur.education.content.TextAnswer
import com.vayunmathur.education.content.TraceAnswer
import com.vayunmathur.education.data.Deadline
import com.vayunmathur.education.data.Learner
import com.vayunmathur.education.data.SkillProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EducationViewModelTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app
    private val store = androidx.lifecycle.ViewModelStore()
    private val collectScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private lateinit var vm: EducationViewModel

    @Before fun setUp() = runBlocking<Unit> {
        val db = Fx.seedDb(ctx)
        db.clearAllTables()
        vm = androidx.lifecycle.ViewModelProvider(
            store,
            EducationViewModelFactory(app, Fx.content(), Fx.repository(ctx)),
        )[EducationViewModel::class.java]
        // The VM's flows are WhileSubscribed: keep collectors running so .value updates.
        collectScope.launch { vm.learner.collect {} }
        collectScope.launch { vm.progress.collect {} }
        collectScope.launch { vm.deadlines.collect {} }
    }

    @After fun tearDown() { collectScope.cancel(); store.clear() }

    private fun awaitVm(label: String, cond: () -> Boolean) {
        // viewModelScope runs on the (paused) Robolectric main looper: pump it while waiting.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!cond() && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(cond(), "timed out awaiting: $label")
    }

    // --- DAO / repository -------------------------------------------------

    @Test fun daoCrudAndFlows() = runBlocking<Unit> {
        val repo = Fx.repository(ctx)
        assertNull(repo.getLearner())
        repo.upsertLearner(Learner(name = "Maya", gradeLevel = 3, totalStars = 4))
        assertEquals("Maya", repo.getLearner()?.name)
        assertEquals("Maya", repo.learnerFlow().first()?.name)
        repo.upsertLearner(Learner(name = "Leo"))
        assertEquals(1L, repo.getLearner()?.id); assertEquals("Leo", repo.getLearner()?.name)

        repo.upsertSkillProgress(SkillProgress("count", stars = 2, attempts = 1, lastPracticedEpochDay = 10))
        repo.upsertSkillProgress(SkillProgress("add", stars = 3))
        assertEquals(2, repo.getAllSkillProgress().size)
        assertEquals(2, repo.skillProgressFlow().first().size)
        assertEquals(3, repo.getSkillProgress("add")?.stars)
        assertNull(repo.getSkillProgress("nope"))

        repo.upsertDeadline(Deadline(moduleType = "UNIT", moduleId = "u1", dueEpochDay = 100, note = "n"))
        repo.upsertDeadline(Deadline(moduleType = "UNIT", moduleId = "u2", dueEpochDay = 50))
        assertEquals(listOf(50L, 100L), repo.deadlinesFlow().first().map { it.dueEpochDay })
        val found = repo.getDeadlineFor("UNIT", "u1"); assertNotNull(found)
        repo.deleteDeadline(found)
        assertNull(repo.getDeadlineFor("UNIT", "u1"))
        assertEquals(1, repo.deadlinesFlow().first().size)
    }

    @Test fun repositoryIsSingleton() {
        assertTrue(Fx.repository(ctx) === Fx.repository(ctx))
    }

    // --- Content repository ----------------------------------------------

    @Test fun contentRepositoryIndexesAndQueries() {
        val content = Fx.content()
        assertEquals(3, content.courses.size)
        assertEquals(setOf("q-mc", "q-ms", "q-num", "q-text", "q-ord", "q-match", "q-trace"), content.questions.keys)
        assertEquals(Fx.mathCourse, content.course("c-math")); assertNull(content.course("nope"))
        assertEquals(Fx.unit, content.unit("u1")); assertEquals(Fx.lesson, content.lesson("l1"))
        assertEquals(Fx.exercise, content.exercise("ex1")); assertEquals(Fx.unitQuiz, content.exercise("quiz-u1"))
        assertEquals(Fx.challenge, content.exercise("ch1")); assertNull(content.exercise("nope"))
        assertEquals(Fx.mathCourse, content.courseOfUnit("u1")); assertNull(content.courseOfUnit("nope"))
        assertEquals(Fx.allQuestions, content.questionsFor(Fx.exercise))
        assertEquals(listOf("count"), content.skillIdsOf(Fx.unitQuiz.copy(questionIds = listOf("q-mc"))))
        assertTrue(content.skillIdsOfCourse(Fx.mathCourse).containsAll(listOf("count", "add", "read", "write")))
        assertEquals("Math Fun", content.moduleTitle(ModuleType.COURSE, "c-math"))
        assertEquals("Numbers", content.moduleTitle(ModuleType.UNIT, "u1"))
        assertEquals("Counting 1-3", content.moduleTitle(ModuleType.LESSON, "l1"))
        assertNull(content.moduleTitle(ModuleType.LESSON, "nope"))
        assertEquals(listOf(Fx.mathCourse), content.coursesForBand(Band.K2))
        assertEquals(listOf(Fx.readingCourse), content.coursesForBand(Band.ELEMENTARY))
        assertEquals(listOf(Fx.scholarCourse), content.coursesForBand(Band.SCHOLAR))
        assertEquals(listOf(Fx.mathCourse), content.coursesForSubject(com.vayunmathur.education.content.Subject.MATH))
        assertEquals(emptyList(), content.coursesForSubject(com.vayunmathur.education.content.Subject.MATH, Band.SCHOLAR))
        assertEquals(listOf(com.vayunmathur.education.content.Subject.MATH, com.vayunmathur.education.content.Subject.SCIENCE, com.vayunmathur.education.content.Subject.READING), content.subjects)
        val empty = ContentRepository(emptyList())
        assertTrue(empty.courses.isEmpty() && empty.questions.isEmpty() && empty.subjects.isEmpty())
    }

    @Test fun contentRepositoryLoadsBundledAssets() {
        val loaded = ContentRepository.load(ctx)
        assertTrue(loaded.courses.isNotEmpty(), "bundled content packs should parse")
        assertTrue(loaded.questions.isNotEmpty())
        assertTrue(loaded.courses.all { it.units.isNotEmpty() })
    }

    // --- Pure VM logic ------------------------------------------------------

    @Test fun gradeCoversEveryQuestionType() {
        val allRight = mapOf(
            "q-mc" to ChoiceAnswer(1), "q-ms" to MultiChoiceAnswer(setOf(0, 2)),
            "q-num" to NumericAnswer(5.05), "q-text" to TextAnswer(" A "),
            "q-ord" to OrderingAnswer(listOf(0, 1, 2)), "q-match" to MatchingAnswer(listOf(0, 1)),
            "q-trace" to TraceAnswer,
        )
        val perfect = vm.grade(Fx.allQuestions, allRight)
        assertEquals(7, perfect.total); assertEquals(7, perfect.correct); assertEquals(3, perfect.stars)
        assertEquals(3, perfect.perSkillStars["count"])

        val allWrong = mapOf(
            "q-mc" to ChoiceAnswer(0), "q-ms" to MultiChoiceAnswer(setOf(1)),
            "q-num" to NumericAnswer(6.0), "q-text" to TextAnswer("z"),
            "q-ord" to OrderingAnswer(listOf(2, 1, 0)), "q-match" to MatchingAnswer(listOf(1, 0)),
            "q-trace" to ChoiceAnswer(0),
        )
        val zero = vm.grade(Fx.allQuestions, allWrong)
        assertEquals(0, zero.correct); assertEquals(0, zero.stars)
        // Missing answers count as wrong, empty quiz is 0 stars.
        assertEquals(0, vm.grade(Fx.allQuestions, emptyMap()).correct)
        assertEquals(QuizResult(0, 0, 0, emptyMap()), vm.grade(emptyList(), emptyMap()))
    }

    @Test fun bandOfHonoursOverrideAndGrades() = runBlocking<Unit> {
        assertEquals(Band.SCHOLAR, vm.bandOf(null))
        assertEquals(Band.K2, vm.bandOf(Learner(gradeLevel = 0)))
        assertEquals(Band.K2, vm.bandOf(Learner(gradeLevel = 2)))
        assertEquals(Band.ELEMENTARY, vm.bandOf(Learner(gradeLevel = 3)))
        assertEquals(Band.ELEMENTARY, vm.bandOf(Learner(gradeLevel = 5)))
        assertEquals(Band.SCHOLAR, vm.bandOf(Learner(gradeLevel = 6)))
        assertEquals(Band.K2, vm.bandOf(Learner(gradeLevel = 9, bandOverride = "K2")))
        assertEquals(Band.SCHOLAR, vm.bandOf(Learner(gradeLevel = 9, bandOverride = "bogus")))
    }

    @Test fun starsAndStreakBoundaries() {
        assertEquals(0, EducationViewModel.starsFor(0, 0))
        assertEquals(3, EducationViewModel.starsFor(10, 10))
        assertEquals(2, EducationViewModel.starsFor(7, 10))
        assertEquals(1, EducationViewModel.starsFor(4, 10))
        assertEquals(0, EducationViewModel.starsFor(3, 10))
        assertEquals(3, EducationViewModel.MAX_STARS)
        assertEquals(1, EducationViewModel.nextStreak(0, 0, 5))
        assertEquals(4, EducationViewModel.nextStreak(3, 4, 5))
        assertEquals(3, EducationViewModel.nextStreak(3, 5, 5))
        assertEquals(1, EducationViewModel.nextStreak(3, 1, 5))
    }

    // --- VM persistence flows --------------------------------------------

    @Test fun learnerEditsPersistAndPinVerifies() = runBlocking<Unit> {
        val repo = Fx.repository(ctx)
        repo.upsertLearner(Learner(name = "Start", gradeLevel = 1))
        awaitVm("learner seeded") { vm.learner.value?.name == "Start" }
        vm.setName("  Maya  "); awaitVm("name") { runBlocking { repo.getLearner()?.name } == "Maya" }
        vm.setGrade(4); awaitVm("grade") { runBlocking { repo.getLearner()?.gradeLevel } == 4 }
        vm.setAvatar("🐯"); awaitVm("avatar") { runBlocking { repo.getLearner()?.avatar } == "🐯" }
        vm.setDailyGoal(0); awaitVm("goal coerced") { runBlocking { repo.getLearner()?.dailyGoal } == 1 }
        vm.setDailyGoal(5); awaitVm("goal") { runBlocking { repo.getLearner()?.dailyGoal } == 5 }
        vm.setBandOverride(Band.K2); awaitVm("band") { runBlocking { repo.getLearner()?.bandOverride } == "K2" }
        vm.setBandOverride(null); awaitVm("band cleared") { runBlocking { repo.getLearner()?.bandOverride } == null }

        assertFalse(vm.verifyPin("1234"))
        vm.setPin("1234"); awaitVm("pin") { runBlocking { repo.getLearner()?.pinHash } != null }
        // learner StateFlow must refresh for verifyPin (it reads learner.value).
        awaitVm("learner flow pin") { vm.learner.value?.pinHash != null }
        assertTrue(vm.verifyPin("1234")); assertFalse(vm.verifyPin("9999")); assertFalse(vm.verifyPin(""))
    }

    @Test fun concurrentLearnerEditsAllPersist() = runBlocking<Unit> {
        val repo = Fx.repository(ctx)
        repo.upsertLearner(Learner(name = "Start", gradeLevel = 1))
        awaitVm("learner seeded") { vm.learner.value?.name == "Start" }
        // Fire distinct-field edits back to back (as ParentPage does per
        // keystroke plus a grade tap): serialized read-modify-writes must
        // leave every field applied, none clobbered by a stale base row.
        repeat(10) {
            vm.setName("Zoe"); vm.setGrade(7); vm.setDailyGoal(3); vm.setAvatar("🦊")
        }
        awaitVm("all concurrent edits persist") {
            val l = runBlocking { repo.getLearner() }
            l?.name == "Zoe" && l.gradeLevel == 7 && l.dailyGoal == 3 && l.avatar == "🦊"
        }
    }

    @Test fun deadlineSetUpdateRemoveThroughVm() = runBlocking<Unit> {
        val repo = Fx.repository(ctx)
        vm.setDeadline(ModuleType.UNIT, "u1", 100, "first")
        awaitVm("deadline created") { runBlocking { repo.getDeadlineFor("UNIT", "u1")?.dueEpochDay } == 100L }
        vm.setDeadline(ModuleType.UNIT, "u1", 120, "second")
        awaitVm("deadline updated") { runBlocking { repo.getDeadlineFor("UNIT", "u1")?.dueEpochDay } == 120L }
        assertEquals(1, repo.deadlinesFlow().first().size)
        awaitVm("vm deadlines flow") { vm.deadlineFor(ModuleType.UNIT, "u1")?.dueEpochDay == 120L }
        val row = repo.getDeadlineFor("UNIT", "u1")!!
        vm.removeDeadline(row)
        awaitVm("deadline removed") { runBlocking { repo.getDeadlineFor("UNIT", "u1") } == null }
        awaitVm("vm deadline cleared") { vm.deadlineFor(ModuleType.UNIT, "u1") == null }
    }

    @Test fun commitResultPersistsProgressStreakAndStars() = runBlocking<Unit> {
        val repo = Fx.repository(ctx)
        repo.upsertLearner(Learner(name = "Maya", streakCount = 2))
        val result = vm.grade(Fx.allQuestions, mapOf("q-mc" to ChoiceAnswer(1), "q-num" to NumericAnswer(5.0)))
        vm.commitResult(result)
        awaitVm("progress persisted") { runBlocking { repo.getSkillProgress("count") } != null }
        val count = repo.getSkillProgress("count")!!
        assertTrue(count.attempts >= 1); assertTrue(count.lastPracticedEpochDay > 0)
        awaitVm("learner stars") { runBlocking { repo.getLearner()?.totalStars ?: 0 } >= 1 }
        val learner = repo.getLearner()!!
        assertTrue(learner.totalStars >= 1)
        assertTrue(learner.streakCount >= 1)
        // A second commit keeps best-of stars and bumps attempts.
        val before = repo.getSkillProgress("count")!!.attempts
        vm.commitResult(vm.grade(Fx.allQuestions, emptyMap()))
        awaitVm("attempts bumped") { runBlocking { repo.getSkillProgress("count")?.attempts } == before + 1 }
        assertTrue(repo.getSkillProgress("count")!!.stars >= count.stars)
        // Progress flow reflects the map shape the UI consumes.
        awaitVm("progress flow") { vm.progress.value.containsKey("count") }
        val map = vm.progress.value
        assertEquals(repo.getSkillProgress("count")?.stars, map["count"]?.stars)
        // Badge statuses always list every badge; first_star unlocks after earning a star.
        val deadline = System.nanoTime() + 10_000_000_000L
        var statuses = vm.badgeStatuses().first()
        while (statuses.none { it.achievement.id == EducationAchievements.Ids.FIRST_STAR && it.isUnlocked } && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25); statuses = vm.badgeStatuses().first()
        }
        assertEquals(EducationAchievements.STICKERS.size, statuses.size)
        assertTrue(statuses.first { it.achievement.id == EducationAchievements.Ids.FIRST_STAR }.isUnlocked)
        vm.dismissBadge()
    }

    // --- Entities, achievements, narrator --------------------------------

    @Test fun entityDefaultsAndStickers() {
        val learner = Learner()
        assertEquals(Learner.SINGLE_ID, learner.id); assertEquals("🦉", learner.avatar); assertEquals(1, learner.dailyGoal)
        assertEquals(SkillProgress("s"), SkillProgress("s", 0, 0, 0))
        assertEquals("", Deadline(moduleType = "UNIT", moduleId = "u", dueEpochDay = 1).note)
        assertEquals("⭐", EducationAchievements.STICKERS[EducationAchievements.Ids.FIRST_STAR])
        assertEquals(8, EducationAchievements.STICKERS.size)
        val ach = EducationAchievements(ctx)
        assertEquals(8, ach.achievements.size)
        ach.dismissNotification(); assertNull(ach.newAchievement.value)
    }

    @Test fun narratorNoOpsBeforeReady() {
        val narrator = Narrator(ctx)
        narrator.narrate("Hello there")
        narrator.narrate("With clip", "missing-clip.mp3")
        narrator.speak("Speak")
        narrator.speak("")
        narrator.stop()
        narrator.shutdown()
    }

    private class ForeignVm : androidx.lifecycle.ViewModel()

    @Test fun factoryRejectsForeignViewModelClass() {
        val factory = EducationViewModelFactory(app, Fx.content(), Fx.repository(ctx))
        assertEquals(vm.javaClass, factory.create(EducationViewModel::class.java).javaClass)
        try {
            factory.create(ForeignVm::class.java)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Unexpected"))
        }
    }
}
