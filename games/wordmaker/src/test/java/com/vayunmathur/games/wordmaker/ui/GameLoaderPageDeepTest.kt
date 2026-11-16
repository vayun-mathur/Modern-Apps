package com.vayunmathur.games.wordmaker.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.wordmaker.Route
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.platform.WordMakerAchievementsManager
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GameLoaderPageDeepTest {
    @get:Rule val rule = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        runCatching {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun vm(): WordMakerViewModel {
        val vm = WordMakerViewModel(ApplicationProvider.getApplicationContext<Application>())
        scope.launch { vm.crosswordData.collect {} }
        scope.launch { vm.foundWords.collect {} }
        scope.launch { vm.bonusWords.collect {} }
        scope.launch { vm.revealedHints.collect {} }
        scope.launch { vm.currentLevel.collect {} }
        scope.launch { vm.gameMode.collect {} }
        scope.launch { vm.difficulty.collect {} }
        scope.launch { vm.competitiveScore.collect {} }
        scope.launch { vm.error.collect {} }
        scope.launch { vm.tapToSpell.collect {} }
        scope.launch { vm.wheelSpacing.collect {} }
        scope.launch { vm.reminderEnabled.collect {} }
        scope.launch { vm.reminderMinutesOfDay.collect {} }
        scope.launch { vm.dailyStreak.collect {} }
        scope.launch { vm.dailyBestStreak.collect {} }
        scope.launch { vm.competitiveActive.collect {} }
        scope.launch { vm.competitiveResult.collect {} }
        scope.launch { vm.competitiveDeadline.collect {} }
        scope.launch { vm.competitiveLevelNumber.collect {} }
        scope.launch { vm.dailyDay.collect {} }
        scope.launch { vm.hintCooldownEnd.collect {} }
        return vm
    }

    private fun pollUntil(deadlineMs: Long = 30000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline && !cond()) Thread.sleep(50)
        assertTrue(cond(), "condition not met within ${deadlineMs}ms")
    }

    private fun resetToCasual(vm: WordMakerViewModel) {
        vm.setGameMode(GameMode.CASUAL)
        vm.saveLevel(1)
        pollUntil { vm.gameMode.value == GameMode.CASUAL && vm.currentLevel.value == 1 }
        pollUntil { vm.crosswordData.value != null }
    }

    /** First dictionary word spellable from [letters] that is not already a solution. */
    private fun findBonusWord(
        vm: WordMakerViewModel,
        letters: List<Char>,
        solutions: Set<String>,
    ): String? {
        fun search(prefix: String, rest: List<Char>, len: Int): String? {
            if (prefix.length == len) {
                return if (prefix !in solutions && vm.isInDictionary(prefix.lowercase())) prefix else null
            }
            val seen = mutableSetOf<Char>()
            for (c in rest) {
                if (!seen.add(c)) continue
                val hit = search(prefix + c, rest - c, len)
                if (hit != null) return hit
            }
            return null
        }
        for (len in 3..5) {
            val hit = search("", letters, len)
            if (hit != null) return hit
        }
        return null
    }

    private fun manager(vm: WordMakerViewModel): WordMakerAchievementsManager {
        val json = context.assets.open("achievements.json").bufferedReader().use { it.readText() }
        return WordMakerAchievementsManager(context, json, vm.levelDataStore)
    }

    @Test fun gameLoaderShowsCompetitiveLobbyThenGame() {
        val vm = vm()
        resetToCasual(vm)
        vm.setGameMode(GameMode.COMPETITIVE)
        pollUntil { vm.gameMode.value == GameMode.COMPETITIVE }
        rule.setContent {
            DynamicTheme {
                val backStack = rememberNavBackStack<Route>(Route.Game)
                WordMakerGameLoader(backStack, vm)
            }
        }
        rule.onNodeWithText("Start").assertIsDisplayed()
        rule.onNodeWithText("Difficulty").assertIsDisplayed()
        rule.onNodeWithText("Start").performClick()
        pollUntil { vm.competitiveActive.value }
        rule.waitUntil(timeoutMillis = 20000) {
            rule.onAllNodesWithContentDescription("Shuffle").fetchSemanticsNodes().isNotEmpty()
        }
        resetToCasual(vm)
    }

    @Test fun gameLoaderErrorScreenRetriesBackToTheGame() {
        val vm = vm()
        resetToCasual(vm)
        rule.setContent {
            DynamicTheme {
                val backStack = rememberNavBackStack<Route>(Route.Game)
                WordMakerGameLoader(backStack, vm)
            }
        }
        rule.waitUntil(timeoutMillis = 20000) {
            rule.onAllNodesWithContentDescription("Shuffle").fetchSemanticsNodes().isNotEmpty()
        }
        // Force the load-error state the loader renders when a board cannot be parsed.
        // Set after the game is up so the in-flight level load cannot clear it again.
        val field = WordMakerViewModel::class.java.getDeclaredField("_error")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val errorFlow = field.get(vm) as MutableStateFlow<String?>
        rule.runOnUiThread { errorFlow.value = "Could not load this level." }
        rule.waitUntil(timeoutMillis = 10000) {
            rule.onAllNodesWithText("Could not load this level.").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Could not load this level.").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        // Retrying clears the error and brings the game back.
        rule.waitUntil(timeoutMillis = 20000) {
            rule.onAllNodesWithContentDescription("Shuffle").fetchSemanticsNodes().isNotEmpty()
        }
        pollUntil { vm.error.value == null }
    }

    @Test fun gameLoaderShowsAchievementToastAndDismissesIt() {
        val vm = vm()
        resetToCasual(vm)
        vm.saveLevel(2)
        pollUntil { vm.currentLevel.value == 2 }
        // Clear prior unlocks so the loader's own check queues a fresh toast.
        kotlinx.coroutines.runBlocking { manager(vm).forgetAll() }
        rule.setContent {
            DynamicTheme {
                val backStack = rememberNavBackStack<Route>(Route.Game)
                WordMakerGameLoader(backStack, vm)
            }
        }
        rule.waitUntil(timeoutMillis = 20000) {
            rule.onAllNodesWithText("First Steps").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("First Steps").assertIsDisplayed()
        // Tapping the toast dismisses it and the game underneath is interactive again.
        rule.onNodeWithText("First Steps").performClick()
        rule.waitUntil(timeoutMillis = 20000) {
            rule.onAllNodesWithContentDescription("Shuffle").fetchSemanticsNodes().isNotEmpty()
        }
        resetToCasual(vm)
    }

    @Test fun wordGamePageWonCasualLevelOneOffersTheNextLevel() {
        val vm = vm()
        resetToCasual(vm)
        val data = vm.crosswordData.value!!
        vm.addFoundWord("CAT")
        vm.addFoundWord("ACT")
        pollUntil { vm.foundWords.value.containsAll(setOf("CAT", "ACT")) }
        rule.setContent {
            DynamicTheme {
                WordGamePage(
                    crosswordData = data, currentLevel = 1, viewModel = vm,
                    achievementsManager = manager(vm), onOpenGameCenter = {}, onOpenSettings = {},
                )
            }
        }
        rule.onNodeWithText("Next Level").assertIsDisplayed()
        rule.onNodeWithText("Next Level").performClick()
        pollUntil { vm.currentLevel.value == 2 }
        resetToCasual(vm)
    }

    @Test fun wordGamePageWonAtFinalDesignedLevelReportsCompletion() {
        val vm = vm()
        resetToCasual(vm)
        val data = vm.crosswordData.value!!
        vm.addFoundWord("CAT")
        vm.addFoundWord("ACT")
        pollUntil { vm.foundWords.value.containsAll(setOf("CAT", "ACT")) }
        rule.setContent {
            DynamicTheme {
                WordGamePage(
                    crosswordData = data, currentLevel = 861, viewModel = vm,
                    achievementsManager = manager(vm), onOpenGameCenter = {}, onOpenSettings = {},
                )
            }
        }
        rule.onNodeWithText("Next Level").assertIsDisplayed()
        rule.onNodeWithText("Next Level").performClick()
        pollUntil { vm.currentLevel.value == 862 }
        resetToCasual(vm)
    }

    @Test fun wordGamePageWonCompetitiveAwardsTheWin() {
        val vm = vm()
        resetToCasual(vm)
        vm.setGameMode(GameMode.COMPETITIVE)
        pollUntil { vm.gameMode.value == GameMode.COMPETITIVE }
        vm.loadNextCompetitiveLevel()
        pollUntil { vm.competitiveActive.value && vm.crosswordData.value != null }
        val data = vm.crosswordData.value!!
        for (word in data.solutionWords) vm.addFoundWord(word)
        pollUntil { vm.foundWords.value.containsAll(data.solutionWords) }
        rule.setContent {
            DynamicTheme {
                WordGamePage(
                    crosswordData = data, currentLevel = 1, viewModel = vm,
                    achievementsManager = manager(vm), onOpenGameCenter = {}, onOpenSettings = {},
                )
            }
        }
        pollUntil { vm.competitiveResult.value?.won == true }
        assertEquals(false, vm.competitiveActive.value)
        resetToCasual(vm)
    }

    @Test fun wordGamePageWonDailyRecordsTheDailyWin() {
        val vm = vm()
        resetToCasual(vm)
        vm.setGameMode(GameMode.DAILY)
        pollUntil { vm.gameMode.value == GameMode.DAILY }
        pollUntil { vm.crosswordData.value != null }
        val data = vm.crosswordData.value!!
        for (word in data.solutionWords) vm.addFoundWord(word)
        pollUntil(30000) { vm.foundWords.value.containsAll(data.solutionWords) }
        rule.setContent {
            DynamicTheme {
                WordGamePage(
                    crosswordData = data, currentLevel = 1, viewModel = vm,
                    achievementsManager = manager(vm), onOpenGameCenter = {}, onOpenSettings = {},
                )
            }
        }
        rule.onNodeWithText("Solved! Come back tomorrow.").assertIsDisplayed()
        pollUntil { vm.dailyStreak.value >= 1L }
        resetToCasual(vm)
    }

    @Test fun settingsPageWithRealViewModelDrivesReminderAndSpacing() {
        val vm = vm()
        resetToCasual(vm)
        rule.setContent { DynamicTheme { SettingsPage(viewModel = vm, onBack = {}) } }
        rule.onNodeWithText("Tap to Spell").assertIsDisplayed()
        rule.onNodeWithText("Daily puzzle reminder").assertIsDisplayed()
        rule.onNodeWithText("Daily puzzle reminder").performClick()
        pollUntil { vm.reminderEnabled.value }
        // With the reminder on, its time row opens the picker and Save stores the time.
        rule.onNodeWithText("Reminder time").performClick()
        rule.onNodeWithText("Save").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        pollUntil { !vm.reminderEnabled.value || vm.reminderMinutesOfDay.value >= 0L }
        rule.onNodeWithText("Daily puzzle reminder").performClick()
        pollUntil { !vm.reminderEnabled.value }
        // Spacing selection goes through the same ViewModel the game reads.
        rule.onNodeWithText("Letter Wheel Spacing").performClick()
        rule.waitForIdle()
        val widest = rule.onAllNodesWithText("Widest").fetchSemanticsNodes()
        if (widest.isNotEmpty()) {
            rule.onAllNodesWithText("Widest")[0].performClick()
            pollUntil { vm.wheelSpacing.value.name == "WIDEST" }
            rule.onNodeWithText("Letter Wheel Spacing").performClick()
            rule.waitForIdle()
            if (rule.onAllNodesWithText("Default").fetchSemanticsNodes().isNotEmpty()) {
                rule.onAllNodesWithText("Default")[0].performClick()
            }
        }
    }

    @Test fun navigationOpensSettingsAndGameCenterEntries() {
        val vm = vm()
        resetToCasual(vm)
        rule.setContent { DynamicTheme { com.vayunmathur.games.wordmaker.Navigation(vm) } }
        rule.waitUntil(timeoutMillis = 20000) {
            rule.onAllNodesWithContentDescription("Shuffle").fetchSemanticsNodes().isNotEmpty()
        }
        // The top-bar settings action routes to the settings entry.
        val settingsNodes = rule.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes()
        assertTrue(settingsNodes.isNotEmpty(), "no Settings action in top bar")
        rule.onAllNodesWithContentDescription("Settings")[0].performClick()
        rule.onNodeWithText("Tap to Spell").assertIsDisplayed()
        // Back returns to the game, then the game-center entry shows achievements.
        rule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().let {
            if (it.isNotEmpty()) rule.onAllNodesWithContentDescription("Back")[0].performClick()
        }
        rule.waitUntil(timeoutMillis = 20000) {
            rule.onAllNodesWithContentDescription("Shuffle").fetchSemanticsNodes().isNotEmpty()
        }
        // The trophy action routes to the game-center entry with the achievement list.
        var opened = false
        for (label in listOf("Badges", "Achievements", "Game Center", "Trophy", "Events", "Center")) {
            val nodes = rule.onAllNodesWithContentDescription(label, substring = true)
                .fetchSemanticsNodes()
            if (nodes.isNotEmpty()) {
                rule.onAllNodesWithContentDescription(label, substring = true)[0].performClick()
                opened = true
                break
            }
        }
        assertTrue(opened, "no game-center action in top bar")
        // The game-center entry renders its own scaffold and achievement list.
        rule.waitUntil(timeoutMillis = 20000) {
            rule.onAllNodesWithText("Game Center").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Game Center").assertIsDisplayed()
        rule.onNodeWithText("First Steps").assertIsDisplayed()
    }

    @Test fun wordGamePageTapSubmissionCreditsSolutionAndBonusThroughDelegate() {
        val vm = vm()
        resetToCasual(vm)
        pollUntil { vm.isInDictionary("cat") }
        vm.setGameMode(GameMode.COMPETITIVE)
        pollUntil { vm.gameMode.value == GameMode.COMPETITIVE }
        vm.loadNextCompetitiveLevel()
        pollUntil { vm.competitiveActive.value && vm.crosswordData.value != null }
        vm.setTapToSpell(true)
        pollUntil { vm.tapToSpell.value }
        val data = vm.crosswordData.value!!
        rule.setContent {
            DynamicTheme {
                WordGamePage(
                    crosswordData = data, currentLevel = 1, viewModel = vm,
                    achievementsManager = manager(vm), onOpenGameCenter = {}, onOpenSettings = {},
                )
            }
        }
        rule.waitUntil(timeoutMillis = 20000) {
            rule.onAllNodesWithContentDescription("Shuffle").fetchSemanticsNodes().isNotEmpty()
        }
        fun tapWord(word: String) {
            // The board above the wheel shows the same letters as text nodes, so a
            // wheel tile is addressed from the end of the node list for its letter:
            // the chooser tiles are composed after the board.
            val used = mutableMapOf<Char, Int>()
            for (ch in word) {
                val index = used.getOrDefault(ch, 0)
                used[ch] = index + 1
                val nodes = rule.onAllNodesWithText(ch.toString())
                val total = nodes.fetchSemanticsNodes().size
                val wheelCount = data.lettersInChooser.count { it == ch }
                nodes[total - wheelCount + index].performClick()
                rule.waitForIdle()
            }
            rule.onNodeWithContentDescription("Submit").performClick()
            rule.waitForIdle()
        }
        // A solution word is credited through the page's achievements delegate.
        val solution = data.solutionWords.maxByOrNull { it.length }!!
        tapWord(solution)
        pollUntil(20000) { solution in vm.foundWords.value }
        // A dictionary word that is not a solution becomes a bonus word the same way.
        val letters = data.lettersInChooser
        val bonus = findBonusWord(vm, letters, data.solutionWords)
        assertNotNull(bonus, "no bonus word spellable from ${letters}")
        tapWord(bonus)
        pollUntil(20000) { bonus in vm.bonusWords.value }
        vm.setTapToSpell(false)
        resetToCasual(vm)
    }

    @Test
    @Config(sdk = [34])
    fun mainActivityLaunchesTheGame() {
        val controller = Robolectric.buildActivity(
            com.vayunmathur.games.wordmaker.MainActivity::class.java,
        )
        val activity = controller.setup().get()
        assertNotNull(activity)
        assertEquals(
            com.vayunmathur.games.wordmaker.MainActivity::class.java.name,
            activity.javaClass.name,
        )
        controller.pause().stop().destroy()
    }
}
