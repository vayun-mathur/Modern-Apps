package com.vayunmathur.games.logicgate.domain

import com.vayunmathur.games.logicgate.domain.levels.ChipGroups
import com.vayunmathur.games.logicgate.domain.levels.Chapter
import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.levels.TrayGroup
import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.ChipCatalog
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.CustomChip
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.PinChips
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.Point
import com.vayunmathur.games.logicgate.domain.model.Wire
import com.vayunmathur.games.logicgate.domain.model.pinRemap
import com.vayunmathur.games.logicgate.domain.model.remapChipPins
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomChipTest {

    private fun threeInputs(): Board = Board.Empty
        .addPin(true, 1, Point(0f, 200f))
        .addPin(true, 4, Point(0f, 0f))
        .addPin(true, 8, Point(0f, 100f))
        .addPin(false, 1, Point(400f, 0f))

    @Test
    fun pinsGetDefaultNamesAndChipOrderFollowsTheBoardTopToBottom() {
        val board = threeInputs()
        assertEquals(listOf("a", "b", "c"), board.io.inputs.map { it.name })
        assertEquals(listOf("o"), board.io.outputs.map { it.name })
        val chip = CustomChip("custom:t", "t", "g", board)
        assertEquals(listOf("b", "c", "a"), chip.inputs.map { it.name })
        assertEquals(listOf(4, 8, 1), chip.inputs.map { it.width })
    }

    @Test
    fun removingAPinShiftsLaterIndicesAndDropsItsWires() {
        var board = threeInputs()
        val (c, not) = board.circuit.addGate(Chips.NOT, Point(200f, 200f))
        board = board.copy(circuit = c.copy(wires = listOf(Wire(PinRef(NodeId(0), PinSide.OUT, 0), PinRef(not, PinSide.IN, 0)))))
        board = board.removePin(NodeId(0))
        assertEquals(listOf("b", "c"), board.io.inputs.map { it.name })
        assertEquals(listOf(0, 1), board.circuit.nodes.filterIsInstance<LevelInput>().map { it.index }.sorted())
        assertTrue(board.circuit.wires.isEmpty())
        assertEquals("a", board.addPin(true, 1, Point(0f, 0f)).io.inputs.last().name)
    }

    @Test
    fun renamingTrimsAndIgnoresBlanks() {
        val board = threeInputs()
        assertEquals("sel", board.renamePin(NodeId(1), "  sel ").io.inputs[1].name)
        assertEquals(board, board.renamePin(NodeId(1), "   "))
    }

    @Test
    fun movingAPinRemapsWiresInCircuitsThatUseTheChip() {
        val before = CustomChip("custom:t", "t", "g", threeInputs())
        val moved = before.copy(board = before.board.copy(circuit = before.board.circuit.moveNode(NodeId(0), Point(0f, -50f))))
        val (ins, outs) = assertNotNull(pinRemap(before, moved))
        assertContentEquals(intArrayOf(1, 2, 0), ins)
        assertContentEquals(intArrayOf(0), outs)
        assertNull(pinRemap(before, before))

        val (c0, g) = Circuit().addGate(before.id, Point(0f, 0f))
        val (c1, src) = c0.addGate(Chips.HIGH, Point(-100f, 0f))
        val wired = c1.copy(wires = listOf(Wire(PinRef(src, PinSide.OUT, 0), PinRef(g, PinSide.IN, 2))))
        assertEquals(PinRef(g, PinSide.IN, 0), wired.remapChipPins(before.id, ins, outs).wires.single().to)

        val gone = intArrayOf(0, 1, -1)
        assertTrue(wired.remapChipPins(before.id, gone, outs).wires.isEmpty())
    }

    @Test
    fun catalogUsesIsTransitive() {
        val inner = CustomChip("custom:inner", "inner", "g", Board.Empty)
        val outer = CustomChip("custom:outer", "outer", "g", Board(Circuit().addGate(inner.id, Point(0f, 0f)).first, Board.Empty.io))
        val catalog = ChipCatalog(listOf(inner, outer))
        assertTrue(catalog.uses(outer.id, inner.id))
        assertFalse(catalog.uses(inner.id, outer.id))
        assertEquals(0, catalog[outer.id]?.nandCost)
    }

    @Test
    fun theSandboxOffersOnlyWhatIsUnlocked() {
        assertEquals(Chips.base.toSet(), LevelRegistry.unlockedChips(emptySet()).toSet())
        assertTrue(Chips.NOT in LevelRegistry.unlockedChips(setOf("not")))
        val throughBus = LevelRegistry.levels.takeWhile { it.id != "nibble_swap" }.map { it.id }.toSet()
        assertTrue(Chips.SPLIT8 in LevelRegistry.unlockedChips(throughBus))
        assertFalse(Chips.ADD8 in LevelRegistry.unlockedChips(throughBus))
        val all = LevelRegistry.levels.map { it.id }.toSet()
        assertEquals(Chips.all.map { it.id }.toSet(), LevelRegistry.unlockedChips(all).toSet())
    }

    @Test
    fun trayGroupsPutPinsFirstThenChaptersThenPlayerGroups() {
        val mine = CustomChip("custom:m", "m", "Adders", Board.Empty)
        val catalog = ChipCatalog(listOf(mine))
        val chips = listOfNotNull(catalog[mine.id], Chips[Chips.ADD8], Chips[Chips.NAND]) + PinChips.all
        val groups = ChipGroups.sections(chips, catalog).map { it.group }
        assertEquals(
            listOf(TrayGroup.Pins, TrayGroup.Builtin(Chapter.BASICS), TrayGroup.Builtin(Chapter.ARITHMETIC), TrayGroup.Custom("Adders")),
            groups,
        )
    }
}
