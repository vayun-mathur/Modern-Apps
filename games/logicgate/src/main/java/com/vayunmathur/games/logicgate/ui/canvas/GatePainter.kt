package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Gate
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.LevelOutput
import com.vayunmathur.games.logicgate.domain.model.Node
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.formatValue

/** What every paint call needs. Coordinates are world units; the caller has applied the camera. */
class PaintContext(
    val colors: CircuitColors,
    val measurer: TextMeasurer,
    val layout: CircuitLayout,
    val live: Map<PinRef, Int>,
)

/** Per-node highlight state. [expected] is the probed test row's wanted value for an output. */
data class NodeStyle(val selected: Boolean, val oscillating: Boolean, val expected: Int? = null)

private const val GRID_DOT_MIN_PX = 14f

/** Dot grid in screen space, every two pin spacings. */
fun DrawScope.drawGrid(camera: Camera, color: Color) {
    val step = GateGeometry.PIN_SPACING * 2 * camera.scale
    if (step < GRID_DOT_MIN_PX) return
    val startX = camera.offset.x.mod(step)
    val startY = camera.offset.y.mod(step)
    var x = startX
    while (x < size.width) {
        var y = startY
        while (y < size.height) {
            drawCircle(color, radius = 1.5f, center = Offset(x, y))
            y += step
        }
        x += step
    }
}

fun wireStroke(width: Int): Float = when (width) {
    1 -> 3f
    4 -> 5f
    else -> 6f
}

private fun wirePath(from: Offset, to: Offset) = Path().apply {
    val (c1, c2) = GateGeometry.controls(from, to)
    moveTo(from.x, from.y)
    cubicTo(c1.x, c1.y, c2.x, c2.y, to.x, to.y)
}

/** A wire; 1-bit wires light up when high, buses are colour-coded by width with a hex value. */
fun DrawScope.drawWire(ctx: PaintContext, ends: Pair<Offset, Offset>, width: Int, value: Int?, selected: Boolean) {
    val path = wirePath(ends.first, ends.second)
    val stroke = wireStroke(width)
    if (selected) drawPath(path, ctx.colors.selected.copy(alpha = 0.45f), style = Stroke(stroke + 8f, cap = StrokeCap.Round))
    val color = when {
        width == 1 && value == 1 -> ctx.colors.wireOn
        width == 1 -> ctx.colors.wireOff
        else -> ctx.colors.forWidth(width)
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
    if (width > 1 && value != null) {
        val mid = GateGeometry.samples(ends.first, ends.second, 2)[1]
        drawTag(ctx, formatValue(value, width), mid, color)
    }
}

fun DrawScope.drawGhostWire(ctx: PaintContext, ends: Pair<Offset, Offset>, width: Int) {
    drawPath(
        wirePath(ends.first, ends.second),
        ctx.colors.forWidth(width).copy(alpha = 0.7f),
        style = Stroke(wireStroke(width), cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawTag(ctx: PaintContext, text: String, center: Offset, color: Color) {
    val layout = ctx.measurer.measure(text, TextStyle(fontSize = 10f.toSp(), fontWeight = FontWeight.Bold))
    val w = layout.size.width + 8f
    val h = layout.size.height + 2f
    drawRoundRect(ctx.colors.background, Offset(center.x - w / 2, center.y - h / 2), Size(w, h), CornerRadius(h / 2))
    drawRoundRect(color, Offset(center.x - w / 2, center.y - h / 2), Size(w, h), CornerRadius(h / 2), style = Stroke(1.5f))
    drawText(layout, color, Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

/** Draws [text] centred on [center] at [sizeWorld] world units. */
fun DrawScope.drawCentered(ctx: PaintContext, text: String, center: Offset, sizeWorld: Float, color: Color) {
    val layout = ctx.measurer.measure(text, TextStyle(fontSize = sizeWorld.toSp(), fontWeight = FontWeight.SemiBold))
    drawText(layout, color, Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

fun DrawScope.drawNode(ctx: PaintContext, node: Node, style: NodeStyle) {
    val rect = ctx.layout.rects[node.id] ?: return
    val c = ctx.colors
    val stroke = when {
        style.oscillating -> c.error
        style.selected -> c.selected
        else -> c.gateStroke
    }
    when (node) {
        is Gate -> drawGate(ctx, node, rect, stroke, style)
        is LevelInput -> drawIo(ctx, rect, PinRef(node.id, PinSide.OUT, 0), stroke, null)
        is LevelOutput -> drawIo(ctx, rect, PinRef(node.id, PinSide.IN, 0), stroke, style.expected)
    }
}

private fun DrawScope.drawGate(ctx: PaintContext, gate: Gate, rect: Rect, stroke: Color, style: NodeStyle) {
    val look = GateShapes.look(gate.chipId)
    val outline = GateShapes.outline(rect, look)
    val fill = if (style.oscillating) ctx.colors.error.copy(alpha = 0.25f) else ctx.colors.gateFill
    if (look.shape != GateShape.BOX) drawPinStubs(ctx, gate, rect, look)
    drawPath(outline, fill)
    drawPath(outline, stroke, style = Stroke(if (style.selected) 3f else 2f))
    if (look.shape == GateShape.XOR) drawPath(GateShapes.xorBack(rect), stroke, style = Stroke(2f))
    if (look.inverted) {
        val b = GateShapes.bubbleCenter(rect, look)
        drawCircle(fill, GateShapes.BUBBLE_R, b)
        drawCircle(stroke, GateShapes.BUBBLE_R, b, style = Stroke(2f))
    }
    val label = Chips[gate.chipId]?.label ?: gate.chipId
    if (look.shape == GateShape.BOX) {
        drawCentered(ctx, label, rect.center, LABEL_SIZE, ctx.colors.label)
        drawPinNames(ctx, gate)
    } else {
        val x = (rect.left + GateShapes.bodyRight(rect, look)) / 2 - if (look.shape == GateShape.NOT) 6f else 0f
        drawCentered(ctx, label, Offset(x, rect.center.y), SMALL_LABEL_SIZE, ctx.colors.muted)
    }
}

private fun DrawScope.drawPinStubs(ctx: PaintContext, gate: Gate, rect: Rect, look: GateLook) {
    val pins = ctx.layout.pins[gate.id] ?: return
    val color = ctx.colors.gateStroke
    for (i in pins.inputs.indices) {
        val a = ctx.layout.anchors[PinRef(gate.id, PinSide.IN, i)] ?: continue
        drawLine(color, a, Offset(rect.left + GateShapes.STUB + STUB_OVERLAP, a.y), 2f)
    }
    for (i in pins.outputs.indices) {
        val a = ctx.layout.anchors[PinRef(gate.id, PinSide.OUT, i)] ?: continue
        val start = GateShapes.bodyRight(rect, look) + if (look.inverted) GateShapes.BUBBLE_R * 2 else 0f
        drawLine(color, Offset(start, a.y), a, 2f)
    }
}

private fun DrawScope.drawPinNames(ctx: PaintContext, gate: Gate) {
    val pins = ctx.layout.pins[gate.id] ?: return
    if (pins.inputs.size + pins.outputs.size <= 2 && pins.inputs.all { it.width == 1 }) return
    pins.inputs.forEachIndexed { i, p ->
        val a = ctx.layout.anchors[PinRef(gate.id, PinSide.IN, i)] ?: return@forEachIndexed
        drawLeft(ctx, p.name, Offset(a.x + PIN_NAME_PAD, a.y))
    }
    pins.outputs.forEachIndexed { i, p ->
        val a = ctx.layout.anchors[PinRef(gate.id, PinSide.OUT, i)] ?: return@forEachIndexed
        drawRight(ctx, p.name, Offset(a.x - PIN_NAME_PAD, a.y))
    }
}

private fun DrawScope.drawLeft(ctx: PaintContext, text: String, at: Offset) {
    val l = ctx.measurer.measure(text, TextStyle(fontSize = PIN_NAME_SIZE.toSp()))
    drawText(l, ctx.colors.muted, Offset(at.x, at.y - l.size.height / 2f))
}

private fun DrawScope.drawRight(ctx: PaintContext, text: String, at: Offset) {
    val l = ctx.measurer.measure(text, TextStyle(fontSize = PIN_NAME_SIZE.toSp()))
    drawText(l, ctx.colors.muted, Offset(at.x - l.size.width, at.y - l.size.height / 2f))
}

/** A level input or output: its pin name and current value; outputs also show a probed mismatch. */
private fun DrawScope.drawIo(ctx: PaintContext, rect: Rect, pin: PinRef, stroke: Color, expected: Int?) {
    val p = ctx.layout.pin(pin) ?: return
    val value = if (pin.side == PinSide.OUT) {
        ctx.live[pin]
    } else {
        ctx.layout.circuit.wireInto(pin)?.let { ctx.live[it.from] } ?: 0
    }
    val border = when {
        expected == null -> stroke
        expected == value -> ctx.colors.ok
        else -> ctx.colors.error
    }
    val on = p.width == 1 && value == 1
    drawRoundRect(if (on) ctx.colors.wireOn.copy(alpha = 0.25f) else ctx.colors.ioFill, rect.topLeft, rect.size, CornerRadius(IO_CORNER))
    drawRoundRect(border, rect.topLeft, rect.size, CornerRadius(IO_CORNER), style = Stroke(if (expected != null) 3f else 2f))
    drawCentered(ctx, p.name, Offset(rect.center.x, rect.top + IO_NAME_Y), PIN_NAME_SIZE, ctx.colors.muted)
    val text = value?.let { formatValue(it, p.width) } ?: "–"
    drawCentered(ctx, text, Offset(rect.center.x, rect.center.y + IO_VALUE_DY), LABEL_SIZE, if (on) ctx.colors.wireOn else ctx.colors.label)
    if (expected != null && expected != value) {
        drawCentered(ctx, "≠" + formatValue(expected, p.width), Offset(rect.right - IO_WANT_DX, rect.top + IO_NAME_Y), PIN_NAME_SIZE, ctx.colors.error)
    }
}

/** Pins, with a ring on every pin a dragged wire could land on and a sideways shake on a rejected one. */
fun DrawScope.drawPins(ctx: PaintContext, node: Node, highlight: Set<PinRef>, shake: Pair<PinRef, Float>?) {
    val pins = ctx.layout.pins[node.id] ?: return
    fun pin(ref: PinRef, width: Int) {
        val base = ctx.layout.anchors[ref] ?: return
        val at = if (shake?.first == ref) base + Offset(shake.second, 0f) else base
        val r = if (width == 1) PIN_R else BUS_PIN_R
        if (ref in highlight) drawCircle(ctx.colors.wireOn, r + RING_GAP, at, style = Stroke(2.5f))
        drawCircle(ctx.colors.background, r, at)
        drawCircle(if (shake?.first == ref) ctx.colors.error else ctx.colors.forWidth(width), r, at, style = Stroke(2.5f))
    }
    pins.inputs.forEachIndexed { i, p -> pin(PinRef(node.id, PinSide.IN, i), p.width) }
    pins.outputs.forEachIndexed { i, p -> pin(PinRef(node.id, PinSide.OUT, i), p.width) }
}

private const val LABEL_SIZE = 14f
private const val SMALL_LABEL_SIZE = 9f
private const val PIN_NAME_SIZE = 9f
private const val PIN_NAME_PAD = 9f
private const val STUB_OVERLAP = 4f
private const val PIN_R = 5f
private const val BUS_PIN_R = 6.5f
private const val RING_GAP = 5f
private const val IO_CORNER = 10f
private const val IO_NAME_Y = 10f
private const val IO_VALUE_DY = 5f
private const val IO_WANT_DX = 14f
