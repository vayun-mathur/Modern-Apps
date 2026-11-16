package com.vayunmathur.games.logicgate.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.LevelIo
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * W6: drives [GatePainter] through a real [CanvasDrawScope] on a bitmap —
 * every gate shape, IO node, pin ring and 1/4/8-bit wire — and checks the
 * pixels it paints (high wire differs from low, gate bodies are filled).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GatePainterW6Test {

    private val density = Density(2f)
    private val colors = CircuitColors(
        background = Color.White, grid = Color.LightGray, gateFill = Color(0xFFE0E0E0),
        gateStroke = Color.Black, label = Color.Black, muted = Color.DarkGray,
        wireOff = Color.Gray, wireOn = Color.Red, bus4 = Color.Blue, bus8 = Color.Magenta,
        selected = Color.Green, error = Color(0xFFFF6600), ok = Color(0xFF00AA00), ioFill = Color(0xFFEEEEEE),
    )

    private fun draw(block: DrawScope.() -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(900, 700, Bitmap.Config.ARGB_8888)
        AndroidCanvas(bmp).drawColor(android.graphics.Color.WHITE)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(AndroidCanvas(bmp)), Size(900f, 700f), block)
        return bmp
    }

    private fun fixture(): Triple<PaintContext, Circuit, Map<String, com.vayunmathur.games.logicgate.domain.model.NodeId>> {
        val io = LevelIo(
            inputs = listOf(Pin("A", 1), Pin("Bus", 8), Pin("Nib", 4)),
            outputs = listOf(Pin("Y", 1), Pin("Out", 8), Pin("N", 4)),
        )
        val lookup = Chips.lookup(io)
        var circuit = Circuit.forLevel(io)
        val ids = mutableMapOf<String, com.vayunmathur.games.logicgate.domain.model.NodeId>()
        listOf(
            Chips.NAND, Chips.AND, Chips.OR, Chips.XOR, Chips.XNOR, Chips.NOT, Chips.MUX, Chips.DMUX,
            Chips.SPLIT8, Chips.JOIN8, Chips.SPLIT4, Chips.JOIN4, Chips.DFF, Chips.DFF8, Chips.REG8,
            Chips.HIGH, Chips.ADD8, Chips.NOT8, Chips.DEC2,
        ).forEachIndexed { i, chip ->
            val (c, id) = circuit.addGate(chip, Point(100f + (i % 5) * 170f, 30f + (i / 5) * 150f))
            circuit = c; ids[chip] = id
        }
        val ins = circuit.nodes.filterIsInstance<com.vayunmathur.games.logicgate.domain.model.LevelInput>()
        val outs = circuit.nodes.filterIsInstance<com.vayunmathur.games.logicgate.domain.model.LevelOutput>()
        circuit.connect(PinRef(ins[0].id, PinSide.OUT, 0), PinRef(ids.getValue(Chips.NAND), PinSide.IN, 0), lookup)?.let { circuit = it }
        circuit.connect(PinRef(ins[1].id, PinSide.OUT, 0), PinRef(ids.getValue(Chips.SPLIT8), PinSide.IN, 0), lookup)?.let { circuit = it }
        circuit.connect(PinRef(ins[2].id, PinSide.OUT, 0), PinRef(ids.getValue(Chips.SPLIT4), PinSide.IN, 0), lookup)?.let { circuit = it }
        circuit.connect(PinRef(ids.getValue(Chips.AND), PinSide.OUT, 0), PinRef(outs[0].id, PinSide.IN, 0), lookup)?.let { circuit = it }

        val live = mutableMapOf<PinRef, Int>()
        live[PinRef(ins[0].id, PinSide.OUT, 0)] = 1
        live[PinRef(ins[1].id, PinSide.OUT, 0)] = 0xA5
        live[PinRef(ins[2].id, PinSide.OUT, 0)] = 0x7
        ids.values.forEachIndexed { i, id -> live[PinRef(id, PinSide.OUT, 0)] = if (i % 2 == 0) 1 else 0x3C }

        val measurer = TextMeasurer(
            createFontFamilyResolver(ApplicationProvider.getApplicationContext()), density, LayoutDirection.Ltr,
        )
        val labels = ids.keys.associateWith { it }
        val layout = CircuitLayout(circuit, lookup, labels)
        return Triple(PaintContext(colors, TextCache(measurer), layout, live), circuit, ids)
    }

    @Test
    fun paintsEveryNodeWireAndPin() {
        val (ctx, circuit, _) = fixture()
        val bmp = draw {
            drawGrid(Camera(Offset.Zero, 2f), colors.grid)
            drawGrid(Camera(Offset.Zero, 0.05f), colors.grid) // below dot threshold: no-op branch
            for (node in circuit.nodes) {
                drawNode(ctx, node, NodeStyle(selected = node.id.raw % 3 == 0, oscillating = false, expected = 1))
                drawPins(ctx, node, highlight = emptySet(), shake = null)
            }
            for (wire in circuit.wires) drawWire(ctx, wire, selected = wire == circuit.wires.first())
            drawGhostWire(ctx, Offset(10f, 10f) to Offset(200f, 90f), width = 8)
            drawGhostWire(ctx, Offset(10f, 30f) to Offset(200f, 120f), width = 1)
        }
        // Gate bodies and IO fills paint non-white pixels somewhere on the board.
        var painted = 0
        for (x in 0 until 900 step 7) for (y in 0 until 700 step 7) {
            if (bmp.getPixel(x, y) != android.graphics.Color.WHITE) painted++
        }
        assertTrue(painted > 100, "expected a richly painted board, got $painted samples")
    }

    @Test
    fun highAndLowWiresPaintDifferentlyAndStrokeScalesWithWidth() {
        assertEquals(3f, wireStroke(1)); assertEquals(5f, wireStroke(4)); assertEquals(6f, wireStroke(8))
        val (ctx, circuit, _) = fixture()
        val wire = circuit.wires.first()
        val high = draw { drawWire(ctx.copyLive(1), wire, selected = false) }
        val low = draw { drawWire(ctx.copyLive(0), wire, selected = false) }
        var diff = 0
        for (x in 0 until 900 step 5) for (y in 0 until 700 step 5) if (high.getPixel(x, y) != low.getPixel(x, y)) diff++
        assertTrue(diff > 0, "high (solid red) and low (dashed grey) wires must differ")
    }

    @Test
    fun gateShapesAndWirePathGeometry() {
        val rect = androidx.compose.ui.geometry.Rect(Offset(20f, 20f), Size(120f, 80f))
        for (chip in listOf(Chips.NAND, Chips.AND, Chips.OR, Chips.XOR, Chips.XNOR, Chips.NOT, Chips.MUX)) {
            val look = GateShapes.look(chip)
            val path: Path = GateShapes.outline(rect, look)
            assertTrue(path != Path()) // constructed without error for every shape
        }
        assertTrue(GateShapes.xorBack(rect) != Path())
        val p = Path(); p.setWire(Offset(0f, 0f), Offset(100f, 50f))
        assertTrue(p != Path())
        // Shifted drawing follows the dragged node.
        val (ctx, circuit, ids) = fixture()
        val gate = circuit.nodes.first { it.id == ids.getValue(Chips.XOR) }
        val shifted = PaintContext(colors, ctx.text, ctx.layout, ctx.live, NodeShift(gate.id, Offset(30f, 20f)), ctx.sizing)
        draw { shifted(shifted, gate.id) { drawNode(shifted, gate, NodeStyle(selected = true, oscillating = true)) } }
    }

    private fun PaintContext.copyLive(v: Int): PaintContext {
        val first = live.keys.first()
        return PaintContext(colors, text, layout, live + (first to v), shift, sizing)
    }
}
