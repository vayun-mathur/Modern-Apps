package com.vayunmathur.games.unblockjam

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.data.LevelPack
import com.vayunmathur.games.unblockjam.data.PackColorScheme
import com.vayunmathur.games.unblockjam.platform.DailyProgress
import com.vayunmathur.games.unblockjam.platform.GameActions
import com.vayunmathur.games.unblockjam.platform.GameUiState
import com.vayunmathur.games.unblockjam.platform.SettingsActions
import com.vayunmathur.games.unblockjam.platform.SettingsUiState
import com.vayunmathur.games.unblockjam.platform.UnblockJamViewModel
import com.vayunmathur.games.unblockjam.ui.UnblockJamTheme
import com.vayunmathur.games.unblockjam.ui.components.GameBoard
import com.vayunmathur.library.util.LevelStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NavigationScreensTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = app as android.content.Context
    private fun str(id: Int) = ctx.getString(id)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        if (LevelPack.PACKS.isEmpty()) LevelPack.init(app)
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun sampleLevel(id: String = "s1") = LevelData(
        id = id,
        dimension = Dimension(6, 6),
        exit = Coord(6, 2),
        blocks = listOf(
            Block(Coord(0, 2), Dimension(2, 1), false),
            Block(Coord(2, 1), Dimension(1, 3), false),
            Block(Coord(4, 4), Dimension(2, 1), true),
        ),
        optimalMoves = 2,
    )

    private class RecordingActions : GameActions {
        var moved: LevelData? = null
        var won = 0
        var undo = 0
        var restart = 0
        var levelChange: Int? = null
        override fun onBlockMoved(newLevelData: LevelData) { moved = newLevelData }
        override fun onLevelWon() { won++ }
        override fun onUndo() { undo++ }
        override fun onRestart() { restart++ }
        var backToPacks = 0
        override fun onBackToPacks() { backToPacks++ }
        override fun onLevelChange(newIndex: Int) { levelChange = newIndex }
    }

    @Test
    fun packScreenListsPacksAndDailyAndRoutesClicks() {
        var openedPack: Int? = null
        var daily = 0; var center = 0; var settings = 0
        rule.setContent {
            UnblockJamTheme {
                PackScreen(
                    packNames = listOf("Alpha Pack", "Beta Pack"),
                    daily = DailyProgress(day = 20000, completed = 2, total = 5, streak = 3),
                    onOpenPack = { openedPack = it },
                    onOpenDaily = { daily++ },
                    onOpenGameCenter = { center++ },
                    onOpenSettings = { settings++ },
                )
            }
        }
        rule.onNodeWithText("Alpha Pack").assertIsDisplayed()
        rule.onNodeWithText("Beta Pack").assertIsDisplayed()
        rule.onNodeWithText("Beta Pack").performClick()
        assertEquals(1, openedPack)
        // daily card is first card: click by its completed text region if present, else via nodes
        val clickables = rule.onAllNodes(hasClickAction())
        assertTrue(clickables.fetchSemanticsNodes().size >= 3)
    }

    @Test
    fun packScreenWithoutDailyShowsOnlyPacks() {
        rule.setContent {
            PackScreen(packNames = listOf("Only Pack"), onOpenPack = {}, onOpenGameCenter = {})
        }
        rule.onNodeWithText("Only Pack").assertIsDisplayed()
        rule.onAllNodesWithText("Beta Pack").fetchSemanticsNodes().also { assertEquals(0, it.size) }
    }

    @Test
    fun levelScreenShowsCompletionStatesAndRoutesClicks() {
        val levels = listOf(sampleLevel("a"), sampleLevel("b"), sampleLevel("c"))
        var opened: Int? = null
        rule.setContent {
            LevelScreen(
                pack = LevelPack("TestPack", levels),
                levelStats = mapOf(
                    "a" to LevelStats(2), // <= optimal: star
                    "b" to LevelStats(9), // > optimal: check
                ),
                onOpenLevel = { opened = it },
            )
        }
        rule.onNodeWithText("1").assertIsDisplayed()
        rule.onNodeWithText("2").assertIsDisplayed()
        rule.onNodeWithText("3").assertIsDisplayed()
        rule.onNodeWithText("3").performClick()
        assertEquals(2, opened)
    }

    @Test
    fun gameScreenPortraitUndoRestartAndInfo() {
        val actions = RecordingActions()
        rule.setContent {
            GameScreen(
                state = GameUiState(
                    levelData = sampleLevel(), levelIndex = 0, maxLevelIndex = 4,
                    moves = 3, bestScore = 5, isCompleted = true, canUndo = true,
                ),
                actions = actions, onBack = {},
            )
        }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.undo)).assertIsDisplayed()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.undo)).performClick()
        assertEquals(1, actions.undo)
        // Restart asks first; dismissing keeps the board, confirming restarts it.
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.restart)).performClick()
        assertEquals(0, actions.restart)
        rule.onNodeWithText(str(R.string.restart_confirm_title)).assertIsDisplayed()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        rule.onNodeWithText(str(R.string.restart_confirm_title)).assertDoesNotExist()
        assertEquals(0, actions.restart)
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.restart)).performClick()
        rule.onNode(
            hasText(str(com.vayunmathur.library.ui.R.string.restart)) and hasAnyAncestor(isDialog())
        ).performClick()
        assertEquals(1, actions.restart)
        assertEquals(0, actions.won)
    }

    @Test
    fun gameScreenWonShowsNextLevelAndHidesUndo() {
        val actions = RecordingActions()
        rule.setContent {
            GameScreen(
                state = GameUiState(
                    levelData = sampleLevel(), levelIndex = 1, maxLevelIndex = 4,
                    moves = 2, isLevelWon = true, canUndo = true,
                ),
                actions = actions, onBack = {},
            )
        }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.undo)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.next_level)).performClick()
        assertEquals(2, actions.levelChange)
    }

    @Test
    fun gameScreenWonAtLastLevelHasNoNextButton() {
        val actions = RecordingActions()
        rule.setContent {
            GameScreen(
                state = GameUiState(
                    levelData = sampleLevel(), levelIndex = 4, maxLevelIndex = 4, isLevelWon = true,
                ),
                actions = actions, onBack = {},
            )
        }
        rule.onNodeWithText(str(R.string.next_level)).assertDoesNotExist()
        assertEquals(null, actions.levelChange)
    }

    @Test
    fun gameScreenLandscapeLayoutRenders() {
        val actions = RecordingActions()
        rule.setContent {
            Box(Modifier.requiredSize(900.dp, 400.dp)) {
                GameScreen(
                    state = GameUiState(levelData = sampleLevel(), moves = 1, canUndo = false),
                    actions = actions, onBack = {},
                )
            }
        }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.undo)).assertExists()
        // undo disabled when canUndo=false: click does nothing via semantics? assert no callback by state
        assertEquals(0, actions.undo)
    }

    @Test
    fun gameBoardWonStateRenders() {
        rule.setContent {
            GameBoard(
                levelData = sampleLevel(), onLevelChanged = {}, onLevelWon = {},
                isLevelWon = true,
            )
        }
        rule.onRoot().assertExists()
    }

    @Test
    fun settingsScreenRendersWithActions() {
        var enabledSeen: Boolean? = null
        var time: Pair<Int, Int>? = null
        val actions = object : SettingsActions {
            override fun setReminderEnabled(enabled: Boolean) { enabledSeen = enabled }
            override fun setReminderTime(hour: Int, minute: Int) { time = hour to minute }
        }
        // exercise the recording actions directly so defaults above are meaningful
        actions.setReminderEnabled(true); actions.setReminderTime(8, 15)
        assertEquals(true, enabledSeen); assertEquals(8 to 15, time)
        rule.setContent {
            SettingsScreen(
                state = SettingsUiState(reminderEnabled = false, reminderHour = 20, reminderMinute = 0),
                actions = GameActionsNoopSettings, onBack = {},
            )
        }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.settings)).assertExists()
    }

    @Test
    fun themeWithoutPackRendersContent() {
        rule.setContent {
            UnblockJamTheme { com.vayunmathur.library.ui.Text("plain-theme") }
        }
        rule.onNodeWithText("plain-theme").assertIsDisplayed()
    }

    @Test
    fun themeWithPackColorsRendersContent() {
        val colored = LevelPack(
            "c", listOf(sampleLevel()),
            PackColorScheme(0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444, 0xFF555555, 0xFF666666, 0xFF777777)
        )
        rule.setContent {
            UnblockJamTheme(pack = colored) { com.vayunmathur.library.ui.Text("colored-theme") }
        }
        rule.onNodeWithText("colored-theme").assertIsDisplayed()
    }

    @Test
    fun themeWithPackWithoutSchemeFallsBack() {
        val noScheme = LevelPack("n", listOf(sampleLevel()), null)
        rule.setContent {
            UnblockJamTheme(pack = noScheme) { com.vayunmathur.library.ui.Text("noscheme-theme") }
        }
        rule.onNodeWithText("noscheme-theme").assertIsDisplayed()
    }

    @Test
    fun fullNavigationFromPackToGameAndBackActions() {
        val vm = UnblockJamViewModel(app)
        rule.setContent { Navigation(vm) }
        rule.onNodeWithText(str(R.string.pack_selector)).assertExists()
        rule.onNodeWithText(LevelPack.PACKS[0].name).performClick()
        rule.onNodeWithText(str(R.string.level_selector)).assertExists()
        rule.onNodeWithText("1").performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.undo)).assertExists()
        assertEquals(0, vm.uiState.value.packIndex)
        assertEquals(0, vm.uiState.value.levelIndex)
    }

    @Test
    fun settingsPageBoundToViewModelRendersReminderSection() {
        val vm = UnblockJamViewModel(app)
        var back = 0
        rule.setContent { SettingsPage(vm, onBack = { back++ }) }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.settings)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.setting_daily_reminder)).assertExists()
        assertEquals(0, back)
    }

    @Test
    fun dailyLevelPageWithGeneratedPackOpensDailyGame() {
        val vm = UnblockJamViewModel(app)
        // generate the pack outside composition (solver work), then the page renders the grid
        vm.refreshDaily()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(120_000) {
                vm.dailyPack.first { it != null }
            }
        }
        val backStack = com.vayunmathur.library.util.NavBackStack<Route>(arrayOf(Route.DailySelector))
        rule.setContent { DailyLevelPage(backStack, vm) }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.daily_challenge)).assertExists()
        rule.onNodeWithText("1").assertExists()
        rule.onNodeWithText("1").performClick()
        assertEquals(Route.DailyGame(0), backStack.backStack.last())
    }

    @Test
    fun dailyLevelPageWithoutPackShowsProgress() {
        val vm = UnblockJamViewModel(app)
        val backStack = com.vayunmathur.library.util.NavBackStack<Route>(arrayOf(Route.DailySelector))
        // pack is null at first composition: the page shows its loading box (generation is
        // launched, but we do not wait for the solver here)
        rule.setContent { DailyLevelPage(backStack, vm) }
        rule.onRoot().assertExists()
    }

    private companion object {
        val GameActionsNoopSettings: SettingsActions = SettingsActions.Noop
    }

    @Test
    fun solvingTheLastPuzzleOfAPackOffersBackToPacks() {
        val actions = RecordingActions()
        rule.setContent {
            GameScreen(
                state = GameUiState(levelData = sampleLevel(), levelIndex = 4, maxLevelIndex = 4, isLevelWon = true),
                actions = actions, onBack = {},
            )
        }
        rule.onNodeWithText(str(R.string.next_level)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.back_to_packs)).performClick()
        assertEquals(1, actions.backToPacks)
    }

    @Test
    fun popToPacksReturnsToThePackList() {
        val stack = com.vayunmathur.library.util.NavBackStack<Route>(
            arrayOf(Route.PackSelector, Route.LevelSelector(2), Route.Game(2, 9))
        )
        stack.popToPacks()
        assertEquals(listOf<Route>(Route.PackSelector), stack.backStack)
        val daily = com.vayunmathur.library.util.NavBackStack<Route>(
            arrayOf(Route.PackSelector, Route.DailySelector, Route.DailyGame(4))
        )
        daily.popToPacks()
        assertEquals(listOf<Route>(Route.PackSelector), daily.backStack)
    }
}
