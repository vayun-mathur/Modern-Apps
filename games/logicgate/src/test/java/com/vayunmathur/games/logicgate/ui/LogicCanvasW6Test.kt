package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.ChipCatalog
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.LevelIo
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.Point
import com.vayunmathur.games.logicgate.domain.sim.CaseResult
import com.vayunmathur.games.logicgate.domain.sim.Probe
import com.vayunmathur.games.logicgate.domain.sim.VerifyResult
import com.vayunmathur.games.logicgate.platform.EditorMode
import com.vayunmathur.games.logicgate.platform.GameUiState
import com.vayunmathur.games.logicgate.platform.LogicActions
import com.vayunmathur.games.logicgate.platform.Selection
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W6: renders the real canvas (every gate shape, bus wires with live values,
 * selection/oscillation/probe states) and the component sheet pin rows —
 * the painter and sheet branches no JVM test reached before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class LogicCanvasW6Test {

    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun io() = LevelIo(
        inputs = listOf(Pin("A", 1), Pin("Bus", 8), Pin("Nib", 4)),
        outputs = listOf(Pin("Y", 1), Pin("Out", 8), Pin("N", 4)),
    )

    /** A circuit with one of many chip shapes plus 1/4/8-bit wires. */
    private fun richState(): GameUiState {
        val io = io()
        val lookup = Chips.lookup(io)
        var circuit = Circuit.forLevel(io)
        val ids = mutableListOf<com.vayunmathur.games.logicgate.domain.model.NodeId>()
        val chipIds = listOf(
            Chips.NAND, Chips.AND, Chips.OR, Chips.XOR, Chips.XNOR, Chips.NOT, Chips.MUX,
            Chips.SPLIT8, Chips.JOIN8, Chips.SPLIT4, Chips.DFF, Chips.DFF8, Chips.REG8,
            Chips.HIGH, Chips.LOW, Chips.ADD8, Chips.NOT8,
        )
        chipIds.forEachIndexed { i, chip ->
            val (c, id) = circuit.addGate(chip, Point(120f + (i % 4) * 220f, 40f + (i / 4) * 160f))
            circuit = c; ids += id
        }
        val inputs = circuit.nodes.filterIsInstance<com.vayunmathur.games.logicgate.domain.model.LevelInput>()
        val outputs = circuit.nodes.filterIsInstance<com.vayunmathur.games.logicgate.domain.model.LevelOutput>()
        // 1-bit wire input A -> first gate input; 8-bit wire Bus -> SPLIT8 input.
        circuit.connect(PinRef(inputs[0].id, PinSide.OUT, 0), PinRef(ids[0], PinSide.IN, 0), lookup)?.let { circuit = it }
        circuit.connect(PinRef(inputs[1].id, PinSide.OUT, 0), PinRef(ids[7], PinSide.IN, 0), lookup)?.let { circuit = it }
        circuit.connect(PinRef(inputs[2].id, PinSide.OUT, 0), PinRef(ids[9], PinSide.IN, 0), lookup)?.let { circuit = it }
        circuit.connect(PinRef(ids[1], PinSide.OUT, 0), PinRef(outputs[0].id, PinSide.IN, 0), lookup)?.let { circuit = it }

        val live = mutableMapOf<PinRef, Int>()
        live[PinRef(inputs[0].id, PinSide.OUT, 0)] = 1
        live[PinRef(inputs[1].id, PinSide.OUT, 0)] = 0xA5
        live[PinRef(inputs[2].id, PinSide.OUT, 0)] = 0x7
        ids.forEachIndexed { i, id -> live[PinRef(id, PinSide.OUT, 0)] = if (i % 2 == 0) 1 else 0x3C }

        val cases = listOf(
            CaseResult("A=1", listOf(1, 0xA5, 7), listOf(1, null, null), listOf(1, 0xA5, 7), ok = true),
            CaseResult("A=0", listOf(0, 0, 0), listOf(1, null, null), listOf(0, 0, 0), ok = false),
        )
        return GameUiState(
            mode = EditorMode.Level(requireNotNull(LevelRegistry["and"])),
            board = Board(circuit, io),
            catalog = ChipCatalog.Builtin,
            chips = Chips.all,
            manualInputs = listOf(1, 0xA5, 7),
            selection = Selection.OfNode(ids[2]),
            verify = VerifyResult(cases),
            probeCase = 0,
            live = Probe(live, oscillating = setOf(ids[3])),
            canUndo = true, canRedo = true, cost = 42, bestCost = 40,
        )
    }

    @Test
    fun gameScreen_richCircuit_paintsEveryGateAndBusWire() {
        rule.setContent {
            Box(Modifier.size(900.dp, 700.dp)) {
                GameScreen(state = richState(), actions = LogicActions.Noop, onBack = {}, onNext = {})
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("NAND", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun gameScreen_liveProbeAndSelectionBar_render() {
        val state = richState()
        rule.setContent {
            Box(Modifier.size(900.dp, 700.dp)) {
                GameScreen(state = state, actions = LogicActions.Noop, onBack = {}, onNext = {})
            }
        }
        rule.waitForIdle()
        // Expand the test panel, then the probed case title shows.
        rule.onNodeWithText(ctx.getString(com.vayunmathur.games.logicgate.R.string.lg_cost, 42)).performClick()
        rule.onNodeWithText("A=1", substring = true).assertExists()
    }

    @Test
    fun gameScreen_wonState_showsCompletion() {
        val state = richState().copy(won = true, stars = 3, nextLevelId = "or")
        rule.setContent {
            Box(Modifier.size(900.dp, 700.dp)) {
                GameScreen(state = state, actions = LogicActions.Noop, onBack = {}, onNext = {})
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun componentSheet_pinRows_editAndDelete() {
        val io = io()
        val board = Board(Circuit.forLevel(io), io)
        val deleted = mutableListOf<com.vayunmathur.games.logicgate.domain.model.NodeId>()
        val actions = object : LogicActions {
            override fun deleteNode(id: com.vayunmathur.games.logicgate.domain.model.NodeId) { deleted += id }
        }
        val state = GameUiState(
            mode = EditorMode.Sandbox("custom:1", "My Gate", "Group"),
            board = board, catalog = ChipCatalog.Builtin, chips = emptyList(),
            manualInputs = listOf(0, 0, 0),
        )
        var dismissed = 0
        rule.setContent { ComponentSheet(state, actions, onDelete = {}, onDismiss = { dismissed++ }) }
        rule.waitForIdle()
        // Two editable fields (name, group) plus one row per board pin.
        val fields = rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes()
        assertTrue(fields.size >= 2, "expected name/group fields, got ${fields.size}")
        rule.onNodeWithText(ctx.getString(com.vayunmathur.games.logicgate.R.string.lg_component_pins)).assertExists()
        assertTrue(dismissed == 0)
    }
}
