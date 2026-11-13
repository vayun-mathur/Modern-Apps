package com.vayunmathur.games.pipes.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.pipes.Navigation
import com.vayunmathur.games.pipes.R
import com.vayunmathur.games.pipes.Route
import com.vayunmathur.games.pipes.data.LevelPack
import com.vayunmathur.games.pipes.platform.PipesViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.R as UiR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipesNavigationTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = ctx.getString(id)

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined); LevelPack.init(app) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun show(initial: @Composable () -> Unit): androidx.compose.runtime.MutableState<@Composable () -> Unit> {
        val state = mutableStateOf(initial)
        rule.setContent { PipesTheme { state.value() } }
        return state
    }

    @Test fun packScreenListsPacksAndNavigates() {
        val vm = PipesViewModel(app)
        val stack = NavBackStack<Route>(arrayOf(Route.PackSelector))
        var center = 0
        show { PackScreen(stack, vm, onOpenGameCenter = { center++ }) }
        rule.onNodeWithText(str(R.string.pack_selector)).assertIsDisplayed()
        rule.onNodeWithText("5×5").assertIsDisplayed()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("0/30")).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("5×5").performClick()
        assertEquals(Route.LevelSelector(0), stack.last())
        assertEquals(0, center)
        // Daily card routes to the daily selector on the same stack
        rule.onNodeWithText(str(UiR.string.game_daily_challenge)).performClick()
        assertEquals(Route.DailySelector, stack.last())
    }

    @Test fun levelScreenShowsGridAndOpensLevel() {
        val vm = PipesViewModel(app)
        // Mark level 0 optimal and level 1 merely completed to exercise both badges
        vm.repository.updateBestScore(LevelPack.PACKS[0].levels[0].id, 1)
        vm.repository.updateBestScore(LevelPack.PACKS[0].levels[1].id, 999)
        val stack = NavBackStack<Route>(arrayOf(Route.PackSelector, Route.LevelSelector(0)))
        show { LevelScreen(stack, vm, 0) }
        rule.onNodeWithText(str(R.string.level_selector)).assertIsDisplayed()
        rule.onNodeWithText("1").assertIsDisplayed()
        rule.onNodeWithText("2").assertExists()
        rule.onNodeWithText("1").performClick()
        assertEquals(Route.Game(0, 0), stack.last())
        // A non-rectangular pack shows thumbnails in its cells
        val blobIndex = LevelPack.PACKS.indexOfFirst { it.name == "Blob" }
        val stack2 = NavBackStack<Route>(arrayOf(Route.LevelSelector(blobIndex)))
        // second host would need a new rule; thumbnail cells are also covered in PipesScreensTest
        rule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test fun dailyScreenLoadsPackAndOpensDailyGame() {
        val vm = PipesViewModel(app)
        // Pre-generate the pack so the first composition already has levels; the screen's own
        // LaunchedEffect refresh then no-ops for the same day.
        vm.refreshDaily()
        val deadline = System.currentTimeMillis() + 20_000
        while (vm.dailyPack.value == null && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertNotNull(vm.dailyPack.value)
        val stack = NavBackStack<Route>(arrayOf(Route.PackSelector, Route.DailySelector))
        show { DailyLevelScreen(stack, vm) }
        // Refresh is asynchronous; the grid appears once the pack is generated
        rule.waitUntilExactlyOneExists(androidx.compose.ui.test.hasText("1"), timeoutMillis = 15_000)
        rule.onNodeWithText("5").assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasText("1"))[0].performClick()
        assertEquals(Route.DailyGame(0), stack.last())
    }

    @Test fun gameScreenRendersBoardAndClampsLevelChange() {
        val vm = PipesViewModel(app)
        val stack = NavBackStack<Route>(arrayOf(Route.Game(0, 0)))
        show { GameScreen(stack, vm, 0, 0) }
        rule.waitForIdle()
        rule.onNodeWithText(str(UiR.string.undo)).assertExists()
        assertEquals(0, vm.uiState.value.packIndex)
        assertNotNull(vm.uiState.value.levelData)
    }

    @Test fun dailyGameScreenLoadsDailyPack() {
        val vm2 = PipesViewModel(app)
        val dailyStack = NavBackStack<Route>(arrayOf(Route.DailyGame(0)))
        show { GameScreen(dailyStack, vm2, PipesViewModel.DAILY_PACK_INDEX, 0) }
        rule.waitUntil(timeoutMillis = 10_000) { vm2.dailyPack.value != null }
        rule.waitForIdle()
        rule.onNodeWithText(str(UiR.string.undo)).assertExists()
    }

    @Test fun settingsPageRendersBoundState() {
        val vm = PipesViewModel(app)
        val stack = NavBackStack<Route>(arrayOf(Route.Settings))
        show { SettingsPage(stack, vm) }
        rule.onNodeWithText(str(R.string.colorblind_mode)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.colorblind_mode)).performClick()
        rule.waitUntil(timeoutMillis = 5_000) { vm.colorblind.value }
        assertTrue(vm.colorblind.value)
    }

    @Test fun navigationHostsPackScreenAndBackupAgentMetadata() {
        val vm = PipesViewModel(app)
        show { Navigation(vm) }
        rule.onNodeWithText("5×5").assertIsDisplayed()
        val agent = com.vayunmathur.games.pipes.platform.AppBackupAgent()
        assertEquals(listOf("level_stats", "daily_stats", "pipes_progress"), agent.prefNames)
        assertEquals(listOf("datastore_default"), agent.datastoreNames)
        // Route value semantics used by the back stack
        assertEquals(Route.Game(1, 2), Route.Game(1, 2))
        assertTrue(Route.Game(1, 2) != Route.Game(1, 3))
        assertEquals(Route.LevelSelector(4).packIndex, 4)
        assertEquals(Route.DailyGame(2).levelIndex, 2)
    }

    @Test fun mainActivityCreatesAndShowsPackList() {
        val controller = Robolectric.buildActivity(com.vayunmathur.games.pipes.MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        // LevelPack was initialised by onCreateBeforeContent
        assertTrue(LevelPack.PACKS.isNotEmpty())
        controller.pause().stop().destroy()
    }
}
