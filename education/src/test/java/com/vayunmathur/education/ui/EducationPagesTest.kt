package com.vayunmathur.education.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.education.EducationFixtures as Fx
import com.vayunmathur.education.MainGraph
import com.vayunmathur.education.R
import com.vayunmathur.education.RootNavigation
import com.vayunmathur.education.Route
import com.vayunmathur.education.data.Learner
import com.vayunmathur.education.data.SkillProgress
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.education.util.EducationViewModelFactory
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** VM-backed pages: render against an in-memory Room DB and drive real interactions. */
@RunWith(RobolectricTestRunner::class)
class EducationPagesTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app
    private val store = androidx.lifecycle.ViewModelStore()
    private val collectScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private lateinit var vm: EducationViewModel
    private var screenContent by androidx.compose.runtime.mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(null)
    private var contentSet = false

    /** createComposeRule allows one setContent per activity; swap the lambda instead. */
    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        if (!contentSet) { rule.setContent { screenContent?.invoke() }; contentSet = true }
        screenContent = content
        rule.waitForIdle()
    }
    private fun str(id: Int, vararg a: Any): String = ctx.getString(id, *a)
    private fun uiStr(id: Int): String = ctx.getString(id)

    @Before fun setUp() = runBlocking<Unit> {
        val db = Fx.seedDb(ctx); db.clearAllTables()
        vm = androidx.lifecycle.ViewModelProvider(
            store, EducationViewModelFactory(app, Fx.content(), Fx.repository(ctx)),
        )[EducationViewModel::class.java]
        // Keep the WhileSubscribed flows hot so .value reads (verifyPin, deadlineFor) work.
        collectScope.launch { vm.learner.collect {} }
        collectScope.launch { vm.progress.collect {} }
        collectScope.launch { vm.deadlines.collect {} }
    }

    @After fun tearDown() { collectScope.cancel(); store.clear() }

    private fun stack(vararg routes: Route) = NavBackStack<Route>(routes)

    private fun seedLearner(learner: Learner) = runBlocking<Unit> {
        Fx.repository(ctx).upsertLearner(learner)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (vm.learner.value?.id != learner.id && System.nanoTime() < deadline) {
            rule.waitForIdle(); Thread.sleep(10)
        }
    }

    private fun awaitText(text: String) {
        rule.waitUntil(10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun parentGateRejectsWrongPinAndAcceptsCorrect() = runBlocking<Unit> {
        Fx.repository(ctx).upsertLearner(Learner(name = "Kid"))
        vm.setPin("1234")
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!vm.verifyPin("1234") && System.nanoTime() < deadline) Thread.sleep(10)
        // learner flow may lag the DB write; wait until VM observes the hash.
        while (vm.learner.value?.pinHash == null && System.nanoTime() < deadline) { rule.waitForIdle(); Thread.sleep(10) }
        val bs = stack(Route.Home, Route.ParentGate)
        show { ParentGatePage(bs, vm) }
        awaitText(str(R.string.grown_ups_only))
        rule.onNodeWithText(str(R.string.pin)).performTextInput("99")
        // Too short: Enter stays disabled; lengthen with wrong digits.
        rule.onNodeWithText(str(R.string.pin)).performTextInput("99")
        rule.onNodeWithText(str(R.string.enter)).performClick()
        awaitText(str(R.string.incorrect_pin))
        assertEquals(Route.ParentGate, bs.last())
        rule.onNodeWithText(str(R.string.pin)).performTextReplacement("1234")
        rule.onNodeWithText(str(R.string.enter)).performClick()
        rule.waitForIdle()
        assertEquals(Route.Parent, bs.last())

        val bs2 = stack(Route.Home, Route.ParentGate)
        show { ParentGatePage(bs2, vm) }
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.cancel), useUnmergedTree = true).performClick()
        assertEquals(Route.Home, bs2.last())
    }

    @Test fun parentPageRendersAndEdits() {
        seedLearner(Learner(name = "Maya", gradeLevel = 4, streakCount = 3, totalStars = 7, dailyGoal = 2))
        runBlocking { Fx.repository(ctx).upsertSkillProgress(SkillProgress("count", stars = 2, attempts = 1)) }
        val bs = stack(Route.Home, Route.Parent)
        show { ParentPage(bs, vm) }
        awaitText(str(R.string.parent_settings))
        awaitText("Maya")
        rule.onNodeWithText(str(R.string.learner)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(str(R.string.progress)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(str(R.string.module_deadlines)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Math Fun").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Numbers").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(str(R.string.security)).performScrollTo().assertIsDisplayed()
        // Change the name and goal; both persist through the VM.
        rule.onNodeWithText("Maya").performScrollTo().performTextReplacement("Mayaa")
        val repo = Fx.repository(ctx)
        val deadline = System.nanoTime() + 10_000_000_000L
        var name = ""
        while (System.nanoTime() < deadline) {
            name = runBlocking { repo.getLearner()?.name.orEmpty() }
            if (name == "Mayaa") break
            Thread.sleep(10); rule.waitForIdle()
        }
        assertEquals("Mayaa", name)
        // Daily goal + button
        rule.onNodeWithText("+").performScrollTo().performClick()
        var goal = 0
        while (System.nanoTime() < deadline) {
            goal = runBlocking { repo.getLearner()?.dailyGoal ?: 0 }
            if (goal == 3) break
            Thread.sleep(10)
        }
        assertEquals(3, goal)
        // Set a PIN via the security row.
        rule.onNodeWithText(str(R.string.new_pin)).performScrollTo().performTextInput("4321")
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.save), useUnmergedTree = true).performScrollTo().performClick()
        var pinSet = false
        while (System.nanoTime() < deadline) {
            pinSet = runBlocking { repo.getLearner()?.pinHash } != null
            if (pinSet) break
            Thread.sleep(10)
        }
        assertTrue(pinSet)
        // Set-date flow opens the date picker dialog; dismiss it.
        rule.onAllNodesWithText(str(R.string.set_date))[0].performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.cancel), useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    @Test fun parentPageGradeAndBandDropdowns() {
        seedLearner(Learner(name = "Maya", gradeLevel = 1))
        val bs = stack(Route.Home, Route.Parent)
        show { ParentPage(bs, vm) }
        awaitText(str(R.string.parent_settings))
        // Grade dropdown lists kindergarten + numbered grades.
        rule.onNodeWithText(str(R.string.grade_number, 1)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.grade_number, 5)).performClick()
        val repo = Fx.repository(ctx)
        val deadline = System.nanoTime() + 10_000_000_000L
        var grade = -1
        while (System.nanoTime() < deadline) {
            grade = runBlocking { repo.getLearner()?.gradeLevel ?: -1 }
            if (grade == 5) break
            Thread.sleep(10)
        }
        assertEquals(5, grade)
    }

    @Test fun explorerHomeSearchAndNavigate() {
        // Grade 4 is the Explorer (elementary) band: only elementary courses are listed.
        seedLearner(Learner(name = "Leo", gradeLevel = 4, streakCount = 2, totalStars = 5))
        val bs = stack(Route.Home)
        show { ExplorerHomePage(bs, vm) }
        awaitText("Reading Time")
        rule.onNodeWithText("Reading Time").assertIsDisplayed()
        // Searching for a non-band topic filters everything out.
        rule.onNodeWithText(str(R.string.find_a_topic)).performTextInput("math")
        rule.waitForIdle()
        assertTrue(onAllNodesWithText("Reading Time").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText(str(R.string.find_a_topic)).performTextReplacement("read")
        rule.waitForIdle()
        rule.onNodeWithText("Reading Time").performClick()
        assertEquals(Route.Course("c-read"), bs.last())
        // Continue card jumps to the first unmastered unit.
        val bs2 = stack(Route.Home)
        show { ExplorerHomePage(bs2, vm) }
        awaitText(str(R.string.jump_back_in))
        rule.onNodeWithText("Stories").performClick()
        assertEquals(Route.UnitScreen("u-read"), bs2.last())
    }

    @Test fun explorerCourseShowsPathAndMissing() {
        seedLearner(Learner(name = "Leo", gradeLevel = 4))
        runBlocking { Fx.repository(ctx).upsertSkillProgress(SkillProgress("count", stars = 3)) }
        val bs = stack(Route.Home, Route.Course("c-math"))
        show { ExplorerCoursePage(bs, vm, "c-math") }
        awaitText("Numbers")
        rule.onNodeWithText("Math Fun").assertIsDisplayed()
        rule.onNodeWithText("Big challenge").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Numbers").performClick()
        assertEquals(Route.UnitScreen("u1"), bs.last())

        val bs2 = stack(Route.Home)
        show { ExplorerCoursePage(bs2, vm, "missing-course") }
        awaitText(str(R.string.this_topic_is_unavailable))
    }

    @Test fun unitAndLessonPagesNavigate() {
        seedLearner(Learner(name = "Leo", gradeLevel = 1))
        val bs = stack(Route.Home, Route.Course("c-math"), Route.UnitScreen("u1"))
        show { UnitPage(bs, vm, "u1") }
        awaitText("Counting 1-3")
        rule.onNodeWithText("Unit quiz").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Counting 1-3").performClick()
        assertEquals(Route.LessonScreen("l1"), bs.last())

        val bs2 = stack(Route.Home, Route.UnitScreen("u1"), Route.LessonScreen("l1"))
        show { LessonPage(bs2, vm, "l1") }
        awaitText("Counting video")
        rule.onNodeWithText("Counting practice").performClick()
        assertEquals(Route.Quiz("ex1"), bs2.last())

        val bs3 = stack(Route.Home)
        show { UnitPage(bs3, vm, "missing") }
        awaitText(str(R.string.this_unit_is_unavailable))
        val bs4 = stack(Route.Home)
        show { LessonPage(bs4, vm, "missing") }
        awaitText(str(R.string.this_lesson_is_unavailable))
    }

    @Test fun k2HomeLessonAndReward() {
        seedLearner(Learner(name = "Kid", gradeLevel = 1, streakCount = 2, totalStars = 3))
        val bs = stack(Route.Home)
        show { K2HomePage(bs, vm) }
        awaitText("Counting 1-3")
        rule.onNodeWithText("Counting 1-3").performClick()
        assertEquals(Route.K2Lesson("l1"), bs.last())

        val bs2 = stack(Route.Home, Route.K2Lesson("l-k2"))
        show { K2LessonPage(bs2, vm, "l-k2") }
        awaitText(str(R.string.play))
        rule.onNodeWithText(str(R.string.play)).performClick()
        assertEquals(Route.K2Quiz("ex-k2"), bs2.last())

        val bs3 = stack(Route.Home, Route.K2Lesson("l-k2"))
        show { K2RewardPage(bs3, 2) }
        awaitText(str(R.string.you_did_it))
        rule.onNodeWithText("⭐⭐").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.home_1)).performClick()
        assertEquals(listOf(Route.Home), bs3.backStack)

        val bs4 = stack(Route.Home)
        show { K2LessonPage(bs4, vm, "missing") }
        awaitText(str(R.string.let_s_go_back))
    }

    @Test fun badgesResultsAndDispatchers() {
        seedLearner(Learner(name = "Maya", gradeLevel = 8, streakCount = 4, totalStars = 9))
        val bs = stack(Route.Home, Route.Badges)
        show { BadgesPage(bs, vm) }
        awaitText("First Star")
        rule.onNodeWithText("First Star").assertIsDisplayed()

        val bs2 = stack(Route.Home, Route.Quiz("ex1"), Route.Results(3, 2, 2))
        show { ResultsPage(bs2, vm, total = 3, correct = 2, stars = 2) }
        awaitText(str(R.string.correct, 2, 3))
        rule.onNodeWithText(str(R.string.home)).performClick()
        assertEquals(listOf(Route.Home), bs2.backStack)

        // Band dispatch: grade 1 -> Explorer course shell, grade 8 -> Scholar course shell.
        val bs3 = stack(Route.Home)
        show { CoursePage(bs3, vm, "c-math") }
        awaitText("Numbers")

        val bs4 = stack(Route.Home)
        show { HomePage(bs4, vm) }
        awaitText("Math Fun")

        // Root navigation waits for the learner, then shows the graph.
        show { RootNavigation(vm) }
        awaitText("Math Fun")
        show { MainGraph(vm) }
        awaitText("Math Fun")
    }

    @Test fun k2RewardStarCountsClamp() {
        val bs = stack(Route.Home)
        show { K2RewardPage(bs, 0) }
        awaitText("⭐")
        show { K2RewardPage(bs, 9) }
        awaitText("⭐⭐⭐")
    }

    @Test fun parentBandOverrideAndDeadlineDelete() = runBlocking<Unit> {
        seedLearner(Learner(name = "Maya", gradeLevel = 4))
        val repo = Fx.repository(ctx)
        repo.upsertDeadline(com.vayunmathur.education.data.Deadline(moduleType = "UNIT", moduleId = "u1", dueEpochDay = 20_000))
        val bs = stack(Route.Home, Route.Parent)
        show { ParentPage(bs, vm) }
        awaitText(str(R.string.parent_settings))
        // With a deadline set, that unit's button reads Change and shows a delete action.
        rule.onNodeWithText(str(R.string.change)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete").performScrollTo().performClick()
        val deadline = System.nanoTime() + 10_000_000_000L
        var deleted = false
        while (System.nanoTime() < deadline) {
            deleted = runBlocking { repo.getDeadlineFor("UNIT", "u1") } == null
            if (deleted) break
            Thread.sleep(10); rule.waitForIdle()
        }
        assertTrue(deleted)
        // Band override dropdown: pick Explorer explicitly.
        rule.onNodeWithText(str(R.string.automatic_by_grade)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("3-5 · Explorer").performClick()
        var band: String? = null
        while (System.nanoTime() < deadline) {
            band = runBlocking { repo.getLearner()?.bandOverride }
            if (band == "ELEMENTARY") break
            Thread.sleep(10)
        }
        assertEquals("ELEMENTARY", band)
        // Deadline dialog OK path: open, pick the dialog's OK (disabled until a date is chosen, so just dismiss via Cancel).
        rule.onAllNodesWithText(str(R.string.set_date))[0].performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.cancel), useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    @Test fun scholarHomeDeadlineAndParentAreaRoutes() = runBlocking<Unit> {
        seedLearner(Learner(name = "Maya", gradeLevel = 8, onboarded = false))
        Fx.repository(ctx).upsertDeadline(com.vayunmathur.education.data.Deadline(moduleType = "UNIT", moduleId = "u1", dueEpochDay = 20_000))
        val bs = stack(Route.Home)
        show { HomePage(bs, vm) }
        // Scholar home lists the deadline by its module title and routes to the unit.
        awaitText("Numbers")
        rule.onNodeWithText("Numbers").performScrollTo().performClick()
        assertEquals(Route.UnitScreen("u1"), bs.last())
    }

    private fun onAllNodesWithText(text: String) = rule.onAllNodesWithText(text)
}
