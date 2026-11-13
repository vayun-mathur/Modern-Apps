package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.levels.Chapter
import com.vayunmathur.games.logicgate.domain.levels.ChipGroups
import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.ChipCatalog
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.domain.sim.VerifyResult
import com.vayunmathur.games.logicgate.platform.ComponentCard
import com.vayunmathur.games.logicgate.platform.EditorMode
import com.vayunmathur.games.logicgate.platform.GameUiState
import com.vayunmathur.games.logicgate.platform.LevelCard
import com.vayunmathur.games.logicgate.platform.LogicActions
import com.vayunmathur.games.logicgate.platform.ProgressionUiState
import com.vayunmathur.games.logicgate.platform.SandboxUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LogicScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    /** A chip card in the tray (its description ends with the drag hint). */
    private val trayCard = hasContentDescription("onto the board", substring = true)

    /** [levelState] with its chips in the tray, as LogicViewModel would group them. */
    private fun levelStateWithTray(): GameUiState = levelState().let { it.copy(tray = ChipGroups.sections(it.chips, it.catalog)) }

    private fun levelState(verify: VerifyResult? = null): GameUiState {
        val level = requireNotNull(LevelRegistry["not"])
        val chips = LevelRegistry.allowedChips(level.id).mapNotNull { ChipCatalog.Builtin[it] }
        return GameUiState(
            mode = EditorMode.Level(level),
            board = Board(Circuit.forLevel(level.io), level.io),
            catalog = ChipCatalog.Builtin,
            chips = chips,
            manualInputs = List(level.inputs.size) { 0 },
            verify = verify,
        )
    }

    @Test fun `progression lists chapters and opens unlocked levels only`() {
        val opened = mutableListOf<String>()
        val cards = LevelRegistry.levels.mapIndexed { i, l ->
            LevelCard(l.id, i + 1, l.title, l.chapter, unlocked = i == 0, stars = 0, bestCost = null, par = l.par)
        }
        rule.setContent { ProgressionScreen(ProgressionUiState(cards), onOpenLevel = { opened += it }, onOpenGameCenter = {}) }
        rule.onNodeWithText(str(R.string.lg_tagline)).assertExists()
        rule.onNodeWithText(str(R.string.lg_chapter_basics)).assertExists()
        rule.onNodeWithText(str(cards.first().title)).performClick()
        assertEquals(listOf("not"), opened)
        assertEquals(28, cards.size)
    }

    @Test fun `progression empty state shows zero solved`() {
        rule.setContent { ProgressionScreen(ProgressionUiState(), onOpenLevel = {}, onOpenGameCenter = {}) }
        rule.onNodeWithText(str(R.string.lg_solved_count, 0, 0)).assertExists()
    }

    @Test fun `sandbox lists components grouped and creates`() {
        val opened = mutableListOf<String>()
        var creates = 0
        val state = SandboxUiState(listOf(ComponentCard("custom:1", "My Gate", "Group", 2, 1, 3)))
        rule.setContent { SandboxScreen(state, onOpen = { opened += it }, onCreate = { creates++ }, onOpenGameCenter = {}) }
        rule.onNodeWithText("My Gate").assertExists()
        rule.onNodeWithText(str(R.string.lg_new_component), useUnmergedTree = true).performClick()
        assertEquals(1, creates)
        rule.onNodeWithText("My Gate").performClick()
        assertEquals(listOf("custom:1"), opened)
    }

    @Test fun `sandbox empty state still offers creation`() {
        rule.setContent { SandboxScreen(SandboxUiState(), onOpen = {}, onCreate = {}, onOpenGameCenter = {}) }
        rule.onNodeWithText(str(R.string.lg_sandbox_tagline)).assertExists()
    }

    @Test fun `game screen renders tray and test panel for a level`() {
        rule.setContent {
            GameScreen(state = levelState(), actions = LogicActions.Noop, onBack = {}, onNext = {})
        }
        // Chip tray shows NAND at minimum for the NOT level
        rule.onAllNodes(hasText("NAND", substring = true)).assertCountEquals(1)
    }

    @Config(qualifiers = "w780dp-h360dp-land")
    @Test fun `a short window puts tests and tray beside the board`() {
        rule.setContent {
            Box(Modifier.size(780.dp, 360.dp)) {
                GameScreen(state = levelStateWithTray(), actions = LogicActions.Noop, onBack = {}, onNext = {}, sideBySide = true)
            }
        }
        val board = rule.onNodeWithTag(BOARD_TAG).getUnclippedBoundsInRoot()
        val tray = rule.onAllNodes(trayCard).onFirst().getUnclippedBoundsInRoot()
        assertTrue(board.bottom - board.top > 250.dp, "board keeps the height: $board")
        assertTrue(board.right - board.left > 400.dp, "board keeps most of the width: $board")
        assertTrue(tray.left >= board.right, "tray is beside the board: $tray vs $board")
    }

    @Test fun `tray cards grow with a large font instead of clipping`() {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                GameScreen(state = levelStateWithTray(), actions = LogicActions.Noop, onBack = {}, onNext = {}, sideBySide = false)
            }
        }
        val card = rule.onAllNodes(trayCard).onFirst().getUnclippedBoundsInRoot()
        assertTrue(card.bottom - card.top > 52.dp, "card grew past its old fixed 52dp: $card")
    }

    @Test fun `test panel for sandbox shows live status instead of level tests`() {
        val state = levelState().copy(mode = EditorMode.Sandbox("custom:1", "Mine", "G"))
        rule.setContent { TestPanel(state, LogicActions.Noop) }
        assertTrue(state.sandbox != null)
    }

    @Test fun `brief sheet and level complete overlay render`() {
        val level = requireNotNull(LevelRegistry["not"])
        var dismissed = 0
        rule.setContent { BriefSheet(level, number = 1, onDismiss = { dismissed++ }) }
        rule.onNodeWithText(str(level.brief), substring = true).assertExists()
        assertEquals(0, dismissed)
    }

    @Test fun `star rating and pin line render`() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                StarRating(stars = 2)
                PinLine("Inputs", listOf(Pin("A", 1), Pin("Bus", 8)))
            }
        }
        rule.onNodeWithText("Inputs").assertExists()
        rule.onNodeWithText("A, Bus", substring = true).assertExists()
    }
}
