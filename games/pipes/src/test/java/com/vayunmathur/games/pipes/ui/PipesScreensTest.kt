package com.vayunmathur.games.pipes.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.pipes.R
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.RenderPos
import com.vayunmathur.games.pipes.data.computeAdjacency
import com.vayunmathur.games.pipes.data.computeHexAdjacency
import com.vayunmathur.games.pipes.data.hexRenderPositions
import com.vayunmathur.games.pipes.platform.GameBoardUiState
import com.vayunmathur.games.pipes.platform.PackProgress
import com.vayunmathur.games.pipes.platform.DailyProgress
import com.vayunmathur.games.pipes.platform.PipesActions
import com.vayunmathur.games.pipes.platform.PipesGameState
import com.vayunmathur.games.pipes.platform.SettingsActions
import com.vayunmathur.games.pipes.platform.SettingsUiState
import com.vayunmathur.games.pipes.ui.components.LevelThumbnail
import com.vayunmathur.library.ui.R as UiR
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class PipesScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = ctx.getString(id)
    private fun show(initial: @Composable () -> Unit): androidx.compose.runtime.MutableState<@Composable () -> Unit> {
        val state = mutableStateOf(initial)
        rule.setContent { state.value() }
        return state
    }

    private fun rectCells(rows: Int, cols: Int) = buildSet { for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c)) }

    private fun squareLevel(
        rows: Int = 5, cols: Int = 5,
        endpoints: List<EndpointPair> = listOf(
            EndpointPair(0, listOf(CellPos(0, 0), CellPos(4, 4))),
            EndpointPair(1, listOf(CellPos(0, 4), CellPos(4, 0))),
        ),
        bridges: Set<CellPos> = emptySet(),
        portals: Map<CellPos, CellPos> = emptyMap(),
        cells: Set<CellPos> = rectCells(rows, cols),
    ): LevelData {
        val base = computeAdjacency(cells)
        val adjacency = if (portals.isEmpty()) base else {
            val m = base.mapValues { it.value.toMutableSet() }.toMutableMap()
            for ((a, b) in portals) m.getOrPut(a) { mutableSetOf() }.add(b)
            m.mapValues { it.value.toList() }
        }
        return LevelData("t", rows, cols, cells, adjacency, null, endpoints, bridges, endpoints.size, portals)
    }

    private class RecordingActions : PipesActions {
        var starts = mutableListOf<CellPos>(); var extends = mutableListOf<CellPos>()
        var commits = 0; var undos = 0; var restarts = 0
        override fun startDraw(cell: CellPos) { starts += cell }
        override fun extendPath(cell: CellPos) { extends += cell }
        override fun commitDraw() { commits++ }
        override fun onUndo() { undos++ }
        override fun onRestart() { restarts++ }
    }

    @Test fun gameBoardRendersSquareHexBridgePortalAndWonBoards() {
        val square = squareLevel(bridges = setOf(CellPos(2, 2)), portals = mapOf(CellPos(0, 4) to CellPos(4, 0), CellPos(4, 0) to CellPos(0, 4)))
        val paths = PipesGameState(
            paths = mapOf(
                0 to listOf(CellPos(0, 0), CellPos(1, 0), CellPos(2, 0), CellPos(2, 1), CellPos(2, 2), CellPos(3, 2), CellPos(4, 4 - 2)),
                1 to listOf(CellPos(0, 4), CellPos(4, 0)),
            ),
            cellOwner = mapOf(CellPos(2, 2) to 0),
        )
        val content = show { PipesTheme { GameBoard(square, paths, 0, listOf(CellPos(0, 0), CellPos(0, 1)), {}, {}, {}, isLevelWon = false, colorblind = true, modifier = Modifier.size(300.dp)) } }
        rule.waitForIdle(); rule.onRoot().captureToImage()
        val hexCells = rectCells(3, 3)
        val hex = LevelData("h", 3, 3, hexCells, computeHexAdjacency(hexCells), hexRenderPositions(hexCells),
            listOf(EndpointPair(0, listOf(CellPos(0, 0), CellPos(2, 0))), EndpointPair(1, listOf(CellPos(0, 2), CellPos(2, 1)))),
            emptySet(), 2, emptyMap(), "hex")
        content.value = { PipesTheme { GameBoard(hex, PipesGameState(mapOf(0 to listOf(CellPos(0, 0), CellPos(1, 0), CellPos(2, 0)))), null, emptyList(), {}, {}, {}, isLevelWon = true, modifier = Modifier.size(300.dp)) } }
        rule.waitForIdle(); rule.onRoot().captureToImage()
        val empty = squareLevel(cells = emptySet(), endpoints = emptyList())
        content.value = { GameBoard(empty, PipesGameState(), null, emptyList(), {}, {}, {}, isLevelWon = false, modifier = Modifier.size(200.dp)) }
        rule.waitForIdle(); rule.onRoot().captureToImage()
        val wrapCells = rectCells(3, 3)
        val wrapAdj = computeAdjacency(wrapCells).toMutableMap().apply {
            this[CellPos(1, 0)] = getValue(CellPos(1, 0)) + CellPos(1, 2)
            this[CellPos(1, 2)] = getValue(CellPos(1, 2)) + CellPos(1, 0)
        }
        val wrap = LevelData("w", 3, 3, wrapCells, wrapAdj, null,
            listOf(EndpointPair(0, listOf(CellPos(1, 0), CellPos(1, 2)))), emptySet(), 1)
        content.value = { GameBoard(wrap, PipesGameState(mapOf(0 to listOf(CellPos(1, 0), CellPos(1, 2)))), null, emptyList(), {}, {}, {}, isLevelWon = false, modifier = Modifier.size(200.dp)) }
        rule.waitForIdle(); rule.onRoot().captureToImage()
    }

    @Test fun gameBoardTouchDrivesDrawCallbacks() {
        val actions = RecordingActions()
        val level = squareLevel()
        val touchContent = show {
            GameBoard(level, PipesGameState(), null, emptyList(),
                onStartDraw = actions::startDraw, onExtendPath = actions::extendPath,
                onCommitDraw = actions::commitDraw, isLevelWon = false, modifier = Modifier.size(300.dp))
        }
        rule.onRoot().performTouchInput { swipeRight(startX = left + 30f, endX = left + 250f, durationMillis = 200) }
        rule.waitForIdle()
        assertTrue(actions.starts.isNotEmpty(), "starts=${actions.starts}")
        assertEquals(0, actions.starts.first().col)
        assertTrue(actions.commits >= 1, "commits=${actions.commits}")
        // A won board ignores touches entirely
        val won = RecordingActions()
        touchContent.value = {
            GameBoard(level, PipesGameState(), null, emptyList(),
                onStartDraw = won::startDraw, onExtendPath = won::extendPath,
                onCommitDraw = won::commitDraw, isLevelWon = true, modifier = Modifier.size(300.dp))
        }
        rule.onRoot().performTouchInput { swipeRight(startX = left + 30f, endX = left + 250f, durationMillis = 200) }
        assertTrue(won.starts.isEmpty() && won.commits == 0)
    }

    @Test fun gameBoardScreenUndoRestartDialogAndNextLevel() {
        val actions = RecordingActions()
        val level = squareLevel()
        var levelChange = -99; var backs = 0
        val screen = show {
            PipesTheme {
                GameBoardScreen(
                    GameBoardUiState(level, levelIndex = 1, maxLevelIndex = 3, moves = 2, canUndo = true, bestScore = 3),
                    actions, onBack = { backs++ }, onLevelChange = { levelChange = it })
            }
        }
        rule.onNodeWithText(str(UiR.string.undo)).assertIsDisplayed().performClick()
        assertEquals(1, actions.undos)
        rule.onNodeWithText(str(UiR.string.restart)).performClick()
        rule.onNodeWithText(str(R.string.restart_confirm_title)).assertIsDisplayed()
        rule.onNodeWithText(str(UiR.string.cancel)).performClick()
        assertEquals(0, actions.restarts)
        rule.onNodeWithText(str(UiR.string.restart)).performClick()
        // Confirm is the destructive Restart button in the dialog (last matching node)
        rule.onAllNodes(androidx.compose.ui.test.hasText(str(UiR.string.restart)))[1].performClick()
        assertEquals(1, actions.restarts)
        // Won with a next level
        screen.value = {
            PipesTheme {
                GameBoardScreen(
                    GameBoardUiState(level, levelIndex = 1, maxLevelIndex = 3, isLevelWon = true),
                    actions, onBack = {}, onLevelChange = { levelChange = it })
            }
        }
        rule.onNodeWithText(str(UiR.string.next_level)).performClick()
        assertEquals(2, levelChange)
        rule.onNodeWithText(ctx.resources.getQuantityString(R.plurals.level_solved_moves, 0, 0)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.fill_every_cell_hint)).assertDoesNotExist()
        // Every pair joined but cells left empty: the hint replaces the silence
        screen.value = {
            PipesTheme {
                GameBoardScreen(
                    GameBoardUiState(level, levelIndex = 1, maxLevelIndex = 3, showFillHint = true, canUndo = true),
                    actions, onBack = {}, onLevelChange = {})
            }
        }
        rule.onNodeWithText(str(R.string.fill_every_cell_hint)).assertIsDisplayed()
        // Won on the last level: no next button, but a solved message and a way back to the packs
        var toPacks = 0
        screen.value = {
            PipesTheme {
                GameBoardScreen(
                    GameBoardUiState(level, levelIndex = 3, maxLevelIndex = 3, isLevelWon = true, moves = 7),
                    actions, onBack = {}, onLevelChange = {}, onBackToPacks = { toPacks++ })
            }
        }
        rule.onNodeWithText(str(UiR.string.next_level)).assertDoesNotExist()
        rule.onNodeWithText(str(UiR.string.undo)).assertDoesNotExist()
        rule.onNodeWithText("Solved in 7 moves!").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.back_to_packs)).assertIsDisplayed().performClick()
        assertEquals(1, toPacks)
        assertEquals(0, backs)
    }

    @Test fun packListShowsDailyPacksAndRoutesClicks() {
        var openedPack = -1; var daily = 0; var settings = 0; var center = 0
        val content2 = show {
            PipesTheme {
                PackListScreen(
                    packs = listOf(PackProgress("5×5", "rectangular", 10, 30), PackProgress("Tower", "tower", 0, 30)),
                    onOpenPack = { openedPack = it }, onOpenSettings = { settings++ },
                    onOpenGameCenter = { center++ },
                    daily = DailyProgress(day = 20_000, completed = 2, total = 5, streak = 3),
                    onOpenDaily = { daily++ },
                )
            }
        }
        rule.onNodeWithText("5×5").assertIsDisplayed()
        rule.onNodeWithText("10/30").assertIsDisplayed()
        rule.onNodeWithText("Rectangular").assertIsDisplayed()
        rule.onNodeWithText("Tower").performClick()
        assertEquals(1, openedPack)
        // Daily card is present above the packs
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().let { assertTrue(it.size >= 3) }
        assertEquals(0, daily); assertEquals(0, settings); assertEquals(0, center)
        // Without a daily progress, no daily card
        content2.value = { PipesTheme { PackListScreen(listOf(PackProgress("Blob", "blob", 1, 3)), { openedPack = it }, {}, {}) } }
        rule.onNodeWithText("Blob").assertIsDisplayed()
    }

    @Test fun settingsScreenRendersAndRoutesToggles() {
        val calls = mutableListOf<String>()
        val actions = object : SettingsActions {
            override fun setColorblind(enabled: Boolean) { calls += "cb:$enabled" }
            override fun setReminderEnabled(enabled: Boolean) { calls += "rem:$enabled" }
            override fun setReminderTime(hour: Int, minute: Int) { calls += "time:$hour:$minute" }
        }
        var backs = 0
        val sc = show { PipesTheme { SettingsScreen(SettingsUiState(colorblind = false, reminderEnabled = false, reminderHour = 20, reminderMinute = 0), actions) { backs++ } } }
        rule.onNodeWithText(str(R.string.colorblind_mode)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.colorblind_mode)).performClick()
        assertTrue(calls.contains("cb:true"), "calls=$calls")
        sc.value = { PipesTheme { SettingsScreen(SettingsUiState(colorblind = true, reminderEnabled = true, reminderHour = 9, reminderMinute = 45), actions) { backs++ } } }
        rule.onNodeWithText(str(R.string.colorblind_mode)).assertIsDisplayed()
        assertEquals(0, backs)
    }

    @Test fun levelThumbnailVariantsRender() {
        val square = squareLevel()
        val thumb = show { LevelThumbnail(square, Modifier.size(80.dp)) }
        rule.waitForIdle(); rule.onRoot().captureToImage()
        val posLevel = square.copy(renderPositions = square.cells.associateWith { RenderPos(it.col.toFloat(), it.row.toFloat()) })
        thumb.value = { LevelThumbnail(posLevel, Modifier.size(80.dp)) }
        rule.waitForIdle(); rule.onRoot().captureToImage()
        thumb.value = { LevelThumbnail(square.copy(cells = emptySet()), Modifier.size(80.dp)) }
        rule.waitForIdle(); rule.onRoot().captureToImage()
        thumb.value = { LevelThumbnail(square.copy(renderPositions = emptyMap()), Modifier.size(80.dp)) }
        rule.waitForIdle(); rule.onRoot().captureToImage()
        thumb.value = { LevelThumbnail(square.copy(rows = 0, cols = 0, cells = setOf(CellPos(0, 0))), Modifier.size(80.dp)) }
        rule.waitForIdle(); rule.onRoot().captureToImage()
        // Non-rectangular subset (thumbnail path in the level grid)
        val holed = squareLevel(cells = rectCells(3, 3) - CellPos(1, 1), rows = 3, cols = 3)
        thumb.value = { LevelThumbnail(holed, Modifier.size(80.dp)) }
        rule.waitForIdle(); rule.onRoot().captureToImage()
    }
}
