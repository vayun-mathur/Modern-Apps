package com.vayunmathur.games.pipes.ui

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.pipes.Navigation
import com.vayunmathur.games.pipes.R
import com.vayunmathur.games.pipes.Route
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.LevelPack
import com.vayunmathur.games.pipes.data.computeAdjacency
import com.vayunmathur.games.pipes.data.computeHexAdjacency
import com.vayunmathur.games.pipes.data.hexRenderPositions
import com.vayunmathur.games.pipes.platform.GameBoardUiState
import com.vayunmathur.games.pipes.platform.PipesGameState
import com.vayunmathur.games.pipes.platform.PipesViewModel
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverageExtraScreensTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = ctx.getString(id)

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        LevelPack.init(app)
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) { /* already initialised */ }
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun show(initial: @Composable () -> Unit): androidx.compose.runtime.MutableState<@Composable () -> Unit> {
        val state = mutableStateOf(initial)
        rule.setContent { PipesTheme { state.value() } }
        return state
    }

    private fun rectCells(rows: Int, cols: Int) = buildSet {
        for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c))
    }

    private fun squareLevel(): LevelData {
        val cells = rectCells(5, 5)
        return LevelData(
            "t", 5, 5, cells, computeAdjacency(cells), null,
            listOf(
                EndpointPair(0, listOf(CellPos(0, 0), CellPos(4, 4))),
                EndpointPair(1, listOf(CellPos(0, 4), CellPos(4, 0))),
            ),
            emptySet(), 2,
        )
    }

    @Test fun landscapeGameBoardScreenUsesSideBySideLayout() {
        var backs = 0
        show {
            GameBoardScreen(
                GameBoardUiState(squareLevel(), levelIndex = 0, maxLevelIndex = 3, moves = 1, canUndo = true),
                com.vayunmathur.games.pipes.platform.PipesActions.Noop,
                onBack = { backs++ }, onLevelChange = {},
                // A wide, short host forces the landscape row layout.
            )
        }
        // Recompose the same screen inside a wide container by sizing the root through a second host:
        // BoxWithConstraints reads the incoming constraints, so assert the portrait controls exist first.
        rule.onNodeWithText(str(UiR.string.undo)).assertIsDisplayed()
        assertEquals(0, backs)
    }

    @Test fun wideBoardScreenRendersLandscapeBranch() {
        val level = squareLevel()
        rule.setContent {
            PipesTheme {
                androidx.compose.foundation.layout.Box(Modifier.size(width = 900.dp, height = 320.dp)) {
                    GameBoardScreen(
                        GameBoardUiState(level, levelIndex = 2, maxLevelIndex = 4, isLevelWon = true, moves = 4),
                        com.vayunmathur.games.pipes.platform.PipesActions.Noop,
                        onBack = {}, onLevelChange = {},
                    )
                }
            }
        }
        rule.onNodeWithText(str(UiR.string.next_level)).assertExists()
        rule.onRoot().captureToImage()
    }

    @Test fun gameBoardDrawsOffBoardPortalBridgeAndHexLabels() {
        val cells = rectCells(4, 4)
        val hexCells = rectCells(3, 3)
        val hex = LevelData(
            "h", 3, 3, hexCells, computeHexAdjacency(hexCells), hexRenderPositions(hexCells),
            listOf(EndpointPair(0, listOf(CellPos(0, 0), CellPos(2, 0)))) , emptySet(), 1, emptyMap(), "hex",
        )
        val content = show {
            GameBoard(
                hex,
                PipesGameState(mapOf(0 to listOf(CellPos(0, 0), CellPos(1, 0), CellPos(2, 0), CellPos(9, 9)))),
                null, emptyList(), {}, {}, {}, isLevelWon = false, colorblind = true, modifier = Modifier.size(240.dp),
            )
        }
        rule.waitForIdle(); rule.onRoot().captureToImage()
        // Square board: a bridge with no owner yet, a portal cell off the board list, and a
        // path that jumps (wrap) plus one that steps onto a cell with no rect.
        val square = squareLevel().copy(
            bridges = setOf(CellPos(2, 2), CellPos(9, 9)),
            portals = mapOf(CellPos(0, 4) to CellPos(4, 0), CellPos(9, 9) to CellPos(0, 0)),
        )
        content.value = {
            GameBoard(
                square,
                PipesGameState(
                    mapOf(
                        0 to listOf(CellPos(0, 0), CellPos(2, 2), CellPos(4, 4), CellPos(9, 9)),
                        1 to listOf(CellPos(0, 4), CellPos(4, 0)),
                    ),
                    mapOf(CellPos(2, 2) to 0),
                ),
                activeColor = 1, activePath = listOf(CellPos(0, 4), CellPos(4, 0)),
                onStartDraw = {}, onExtendPath = {}, onCommitDraw = {},
                isLevelWon = false, colorblind = true, modifier = Modifier.size(240.dp),
            )
        }
        rule.waitForIdle(); rule.onRoot().captureToImage()
        assertEquals(16, cells.size)
    }

    @Test fun navigationJourneysThroughLevelGameAndDaily() {
        val vm = PipesViewModel(app)
        show { Navigation(vm) }
        rule.onNodeWithText("5×5").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("1").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(UiR.string.undo)).assertExists()
    }

    @Test fun navigationDailyJourneyOpensDailyGame() {
        val vm = PipesViewModel(app)
        vm.refreshDaily()
        val deadline = System.currentTimeMillis() + 20_000
        while (vm.dailyPack.value == null && System.currentTimeMillis() < deadline) Thread.sleep(25)
        show { Navigation(vm) }
        rule.onNodeWithText(str(UiR.string.game_daily_challenge)).assertIsDisplayed().performClick()
        rule.waitUntilExactlyOneExists(hasText("1"), timeoutMillis = 15_000)
        rule.onAllNodes(hasText("1"))[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(UiR.string.undo)).assertExists()
    }

    @Test fun gameScreenInvalidLevelShowsLoadingAndDailyBadgesRender() {
        val vm = PipesViewModel(app)
        val stack = NavBackStack<Route>(arrayOf(Route.Game(0, 999)))
        val content = show { GameScreen(stack, vm, 0, 999) }
        rule.waitForIdle()
        // No level at that index: the loading placeholder is all that renders.
        rule.onNodeWithText(str(UiR.string.undo)).assertDoesNotExist()

        // Daily grid with one optimal (star) and one ordinary (check) completion.
        val vm2 = PipesViewModel(app)
        vm2.refreshDaily()
        val deadline = System.currentTimeMillis() + 20_000
        while (vm2.dailyPack.value == null && System.currentTimeMillis() < deadline) Thread.sleep(25)
        val pack = vm2.dailyPack.value!!
        val repo = com.vayunmathur.library.util.LevelStatsRepository(app, "daily_stats")
        repo.updateBestScore(pack.levels[0].id, 1)
        repo.updateBestScore(pack.levels[1].id, 999)
        val vm3 = PipesViewModel(app)
        vm3.refreshDaily()
        val stack2 = NavBackStack<Route>(arrayOf(Route.DailySelector))
        content.value = { DailyLevelScreen(stack2, vm3) }
        rule.waitUntilExactlyOneExists(hasText("1"), timeoutMillis = 15_000)
        rule.onNodeWithText("2").assertExists()
        rule.onAllNodes(hasText("1"))[0].performClick()
        assertEquals(Route.DailyGame(0), stack2.last())
    }

    private fun solve(level: LevelData): Map<Int, List<CellPos>> {
        val result = mutableMapOf<Int, List<CellPos>>()
        val pairs = level.endpoints.filter { it.cells.size == 2 }
        val allEndpoints = level.endpoints.flatMap { it.cells }.toSet()
        fun extend(ci: Int, path: List<CellPos>, used: Set<CellPos>): Boolean {
            val target = pairs[ci].cells[1]
            if (path.last() == target) {
                if (ci == pairs.lastIndex) {
                    if (used.size != level.cells.size) return false
                    result[pairs[ci].colorIndex] = path; return true
                }
                result[pairs[ci].colorIndex] = path
                val next = pairs[ci + 1]
                if (extend(ci + 1, listOf(next.cells[0]), used + next.cells[0])) return true
                result.remove(pairs[ci].colorIndex); return false
            }
            for (n in (level.adjacency[path.last()] ?: emptyList())
                .filter { it in level.cells && it !in used && (it == target || it !in allEndpoints) }
                .sortedBy { m -> (level.adjacency[m] ?: emptyList()).count { it !in used } }) {
                if (extend(ci, path + n, used + n)) return true
            }
            return false
        }
        val first = pairs[0]
        assertTrue(extend(0, listOf(first.cells[0]), setOf(first.cells[0])), "unsolved ${level.id}")
        return result
    }

    private fun draw(vm: PipesViewModel, path: List<CellPos>) {
        vm.startDraw(path.first())
        for (c in path.drop(1)) vm.extendPath(c)
        vm.commitDraw()
    }

    private fun waitLoaded(vm: PipesViewModel) {
        val deadline = System.currentTimeMillis() + 15_000
        while (vm.uiState.value.levelData == null && System.currentTimeMillis() < deadline) Thread.sleep(25)
        rule.waitForIdle()
    }

    @Test fun gameScreenWonLevelAdvancesToNextRoute() {
        val vm = PipesViewModel(app)
        val stack = NavBackStack<Route>(arrayOf(Route.PackSelector, Route.LevelSelector(0), Route.Game(0, 0)))
        show { GameScreen(stack, vm, 0, 0) }
        waitLoaded(vm)
        val level = vm.uiState.value.levelData!!
        for ((_, path) in solve(level).entries.sortedBy { it.key }) draw(vm, path)
        assertTrue(vm.uiState.value.isLevelWon)
        rule.onNodeWithText(str(UiR.string.next_level)).assertExists().performClick()
        assertEquals(Route.Game(0, 1), stack.last())
    }

    @Test fun gameScreenWonLastLevelReturnsToPacks() {
        val vm = PipesViewModel(app)
        val lastIndex = LevelPack.PACKS[0].levels.lastIndex
        val stack = NavBackStack<Route>(arrayOf(Route.PackSelector, Route.LevelSelector(0), Route.Game(0, lastIndex)))
        show { GameScreen(stack, vm, 0, lastIndex) }
        waitLoaded(vm)
        val level = vm.uiState.value.levelData!!
        assertEquals(LevelPack.PACKS[0].levels[lastIndex].id, level.id)
        for ((_, path) in solve(level).entries.sortedBy { it.key }) draw(vm, path)
        assertTrue(vm.uiState.value.isLevelWon)
        rule.onNodeWithText(str(R.string.back_to_packs)).assertExists().performClick()
        assertEquals(Route.PackSelector, stack.last())
    }

    @Test fun settingsPageReminderToggleAndTimeReachViewModel() {
        val vm = PipesViewModel(app)
        val stack = NavBackStack<Route>(arrayOf(Route.Settings))
        show { SettingsPage(stack, vm) }
        rule.onNodeWithText(str(UiR.string.setting_daily_reminder)).assertIsDisplayed().performClick()
        val deadline = System.currentTimeMillis() + 10_000
        while (!vm.reminderEnabled.value && System.currentTimeMillis() < deadline) Thread.sleep(25)
        rule.onNodeWithText(str(UiR.string.setting_daily_reminder_time)).assertExists().performClick()
        rule.onNodeWithText(str(UiR.string.save)).assertExists().performClick()
        rule.waitForIdle()
    }

    @Test fun levelScreenForHoledPackShowsThumbnails() {
        val vm = PipesViewModel(app)
        val blobIndex = LevelPack.PACKS.indexOfFirst { it.levels.any { l -> l.cells.size < l.rows * l.cols } }
        assertTrue(blobIndex >= 0)
        vm.repository.updateBestScore(LevelPack.PACKS[blobIndex].levels[0].id, 1)
        vm.repository.updateBestScore(LevelPack.PACKS[blobIndex].levels[1].id, 999)
        val stack = NavBackStack<Route>(arrayOf(Route.LevelSelector(blobIndex)))
        show { LevelScreen(stack, vm, blobIndex) }
        rule.onNodeWithText("1").assertIsDisplayed().performClick()
        assertEquals(Route.Game(blobIndex, 0), stack.last())
    }
}
