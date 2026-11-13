package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.Gate
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.LevelOutput
import com.vayunmathur.games.logicgate.domain.model.Node
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.NodePins
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.domain.model.PinLookup
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.Point
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Node sizes, pin anchors and wire curves, all in world units. */
object GateGeometry {
    const val PIN_SPACING = 28f
    const val GRID = 14f
    private const val MIN_HEIGHT = 56f
    /** The narrowest a gate is drawn, in world units. */
    const val GATE_WIDTH = 80f
    private const val IO_WIDTH = 64f
    private const val IO_BUS_WIDTH = 88f
    private const val IO_HEIGHT = 48f
    private const val CHAR_WIDTH = 9f
    private const val LABEL_PAD = 34f
    private const val MIN_CURVE = 40f

    fun size(node: Node, pins: NodePins, label: (String) -> String): Size = when (node) {
        is Gate -> {
            val rows = max(max(pins.inputs.size, pins.outputs.size), 1)
            Size(max(GATE_WIDTH, label(node.chipId).length * CHAR_WIDTH + LABEL_PAD), max(MIN_HEIGHT, rows * PIN_SPACING + GRID))
        }
        is LevelInput -> ioSize(pins.outputs.firstOrNull())
        is LevelOutput -> ioSize(pins.inputs.firstOrNull())
    }

    private fun ioSize(pin: Pin?) = Size(if ((pin?.width ?: 1) > 1) IO_BUS_WIDTH else IO_WIDTH, IO_HEIGHT)

    fun anchor(rect: Rect, side: PinSide, index: Int, count: Int): Offset = Offset(
        if (side == PinSide.IN) rect.left else rect.right,
        rect.center.y + (index - (count - 1) / 2f) * PIN_SPACING,
    )

    fun snap(v: Float): Float = (v / GRID).roundToInt() * GRID

    fun controls(from: Offset, to: Offset): Pair<Offset, Offset> {
        val dx = max(MIN_CURVE, abs(to.x - from.x) * 0.5f)
        return Offset(from.x + dx, from.y) to Offset(to.x - dx, to.y)
    }

    fun samples(from: Offset, to: Offset, n: Int = 20): List<Offset> {
        val (c1, c2) = controls(from, to)
        return (0..n).map { i ->
            val t = i / n.toFloat()
            val u = 1 - t
            from * (u * u * u) + c1 * (3 * u * u * t) + c2 * (3 * u * t * t) + to * (t * t * t)
        }
    }

    fun distanceToPolyline(p: Offset, points: List<Offset>): Float =
        points.zipWithNext().minOfOrNull { (a, b) -> distanceToSegment(p, a, b) } ?: Float.MAX_VALUE

    private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
        val ab = b - a
        val len2 = ab.x * ab.x + ab.y * ab.y
        val t = if (len2 == 0f) 0f else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
        val q = a + ab * t
        return hypot(p.x - q.x, p.y - q.y)
    }
}

/** A circuit measured out for drawing and hit-testing. [labels] maps chip ids to their displayed names. */
class CircuitLayout(val circuit: Circuit, lookup: PinLookup, private val labels: Map<String, String>) {
    fun label(chipId: String): String = labels[chipId] ?: chipId

    val pins: Map<NodeId, NodePins> = circuit.nodes.associate { it.id to (lookup.pins(it) ?: NodePins(emptyList(), emptyList())) }
    val rects: Map<NodeId, Rect> = circuit.nodes.associate { n ->
        n.id to Rect(Offset(n.pos.x, n.pos.y), GateGeometry.size(n, pins.getValue(n.id), ::label))
    }
    val anchors: Map<PinRef, Offset> = buildMap {
        for (n in circuit.nodes) {
            val p = pins.getValue(n.id)
            val r = rects.getValue(n.id)
            p.inputs.indices.forEach { put(PinRef(n.id, PinSide.IN, it), GateGeometry.anchor(r, PinSide.IN, it, p.inputs.size)) }
            p.outputs.indices.forEach { put(PinRef(n.id, PinSide.OUT, it), GateGeometry.anchor(r, PinSide.OUT, it, p.outputs.size)) }
        }
    }

    fun pin(ref: PinRef): Pin? = pins[ref.node]?.pin(ref.side, ref.index)

    val bounds: Rect?
        get() = rects.values.reduceOrNull { a, b ->
            Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
        }

    /**
     * Where to drop a new [chipId] so it is centred near [center] without covering anything:
     * the nearest free grid spot, searching outwards.
     */
    fun freeSpot(chipId: String, center: Offset, lookup: PinLookup): Point {
        val probe = Gate(NodeId(-1), chipId, Point(0f, 0f))
        val size = GateGeometry.size(probe, lookup.pins(probe) ?: NodePins(emptyList(), emptyList()), ::label)
        val base = center - Offset(size.width / 2, size.height / 2)
        val step = GateGeometry.PIN_SPACING * 2
        val candidates = (-SEARCH..SEARCH).flatMap { i -> (-SEARCH..SEARCH).map { j -> Offset(i * step, j * step) } }
            .sortedBy { it.getDistanceSquared() }
        val spot = candidates.map { base + it }.firstOrNull { tl ->
            val r = Rect(tl, size).inflate(GateGeometry.GRID)
            rects.values.none { it.overlaps(r) }
        } ?: base
        return Point(GateGeometry.snap(spot.x), GateGeometry.snap(spot.y))
    }

    private companion object {
        const val SEARCH = 6
    }
}
