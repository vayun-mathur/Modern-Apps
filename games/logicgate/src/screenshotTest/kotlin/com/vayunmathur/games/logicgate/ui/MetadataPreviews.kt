package com.vayunmathur.games.logicgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.logicgate.domain.levels.ChipGroups
import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.levels.TrayGroup
import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.ChipCatalog
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.LevelOutput
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.Point
import com.vayunmathur.games.logicgate.domain.sim.Scoring
import com.vayunmathur.games.logicgate.domain.sim.Verifier
import com.vayunmathur.games.logicgate.platform.EditorMode
import com.vayunmathur.games.logicgate.platform.GameUiState
import com.vayunmathur.games.logicgate.platform.LevelCard
import com.vayunmathur.games.logicgate.platform.LogicActions
import com.vayunmathur.games.logicgate.platform.ProgressionUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:games:logicgate`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview, and must be a class member. Order comes
 * from the function names. Rendering goes through [LogicGateTheme] because the app ships a fixed
 * palette. The circuits are real: verification and live wire values come from the simulator.
 */
class MetadataPreviews {

    private val xor = requireNotNull(LevelRegistry["xor"])
    private val lookup = Chips.lookup(xor.io)

    /** `(a OR b) AND (a NAND b)`, with the board output taken from [outputFrom] (`or` or `and`). */
    private fun xorCircuit(outputFrom: String): Circuit {
        var c = Circuit.forLevel(xor.io)
        val a = c.nodes.first { it is LevelInput && it.index == 0 }.id
        val b = c.nodes.first { it is LevelInput && it.index == 1 }.id
        val o = c.nodes.first { it is LevelOutput }.id
        fun add(chip: String, x: Float, y: Float): NodeId = c.addGate(chip, Point(x, y)).let { (next, id) -> c = next; id }
        fun wire(from: NodeId, to: NodeId, pin: Int) {
            c = requireNotNull(c.connect(PinRef(from, PinSide.OUT, 0), PinRef(to, PinSide.IN, pin), lookup))
        }
        val nand = add(Chips.NAND, 112f, -112f)
        val or = add(Chips.OR, 112f, 56f)
        val and = add(Chips.AND, 266f, -28f)
        wire(a, nand, 0)
        wire(b, nand, 1)
        wire(a, or, 0)
        wire(b, or, 1)
        wire(nand, and, 0)
        wire(or, and, 1)
        wire(if (outputFrom == "and") and else or, o, 0)
        return c
    }

    private fun gameState(circuit: Circuit, probe: Int?, won: Boolean): GameUiState {
        val result = Verifier.verify(xor.io, xor.tests, circuit)
        val cost = Scoring.cost(circuit) { Chips[it]?.nandCost }
        val live = probe?.let { Verifier.probe(xor.io, xor.tests, circuit, it) } ?: Verifier.manual(xor.io, circuit, listOf(1, 0))
        val chips = LevelRegistry.allowedChips(xor.id).mapNotNull { Chips[it] }
        return GameUiState(
            mode = EditorMode.Level(xor),
            board = Board(circuit, xor.io),
            catalog = ChipCatalog.Builtin,
            chips = chips,
            manualInputs = listOf(1, 0),
            tray = ChipGroups.sections(chips, ChipCatalog.Builtin),
            defaultGroup = TrayGroup.Builtin(xor.chapter),
            verify = result,
            probeCase = probe,
            live = live,
            canUndo = true,
            won = won,
            stars = Scoring.stars(result.solved, cost, xor.par),
            cost = cost,
            fitToken = 1,
            nextLevelId = LevelRegistry.next(xor.id)?.id,
        )
    }

    @PreviewTest
    @Preview(name = "1-levels", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Levels() {
        val best = mapOf("not" to 1, "and" to 2, "or" to 3, "nor" to 5)
        val cards = LevelRegistry.levels.mapIndexed { i, l ->
            LevelCard(
                id = l.id,
                number = i + 1,
                title = l.title,
                chapter = l.chapter,
                unlocked = LevelRegistry.isUnlocked(l.id, best.keys),
                stars = best[l.id]?.let { Scoring.stars(true, it, l.par) } ?: 0,
                bestCost = best[l.id],
                par = l.par,
            )
        }
        LogicGateTheme { ProgressionScreen(ProgressionUiState(cards), onOpenLevel = {}, onOpenGameCenter = {}) }
    }

    @PreviewTest
    @Preview(name = "2-circuit", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Circuit() {
        // Output still taken off the OR: the a=1, b=1 row is probed and the output shows the miss.
        LogicGateTheme {
            GameScreen(gameState(xorCircuit("or"), probe = 3, won = false), LogicActions.Noop, onBack = {}, onNext = {})
        }
    }

    @PreviewTest
    @Preview(name = "3-solved", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Solved() {
        LogicGateTheme {
            GameScreen(gameState(xorCircuit("and"), probe = null, won = true), LogicActions.Noop, onBack = {}, onNext = {})
        }
    }
}
