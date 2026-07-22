package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.data.*
import kotlin.math.max
import kotlin.math.min

data class GateRect(val chip: PlacedChip, val left: Float, val top: Float, val width: Float, val height: Float) {
    fun inputPos(i: Int, count: Int): Offset {
        if (count <= 1) return Offset(left, top + height / 2f)
        val gap = height / (count + 1)
        return Offset(left, top + gap * (i + 1))
    }
    fun outputPos(i: Int, count: Int): Offset {
        if (count <= 1) return Offset(left + width, top + height / 2f)
        val gap = height / (count + 1)
        return Offset(left + width, top + gap * (i + 1))
    }
    fun bodyContains(p: Offset): Boolean = p.x in left..left + width && p.y in top..top + height
}

data class IoTerminalLayout(val index: Int, val center: Offset, val name: String, val isInput: Boolean)
private enum class DragMode { None, Gate, InputTerm, OutputTerm, Wire }
private data class HitOutput(val end: WireEnd, val pos: Offset)
private data class HitInput(val end: WireEnd, val pos: Offset)

@Composable
fun CircuitCanvas(
    level: LevelDef,
    gates: List<PlacedChip>,
    wires: List<Wire>,
    outputMaps: List<OutputMapping>,
    inputPositions: Map<Int, IoPos>,
    outputPositions: Map<Int, IoPos>,
    wiringFrom: WireEnd?,
    onCreateWire: (from: WireEnd, to: WireEnd) -> Unit,
    onStartWiring: (WireEnd) -> Unit,
    onCancelWiring: () -> Unit,
    onGateMove: (id: String, x: Float, y: Float) -> Unit,
    onInputTermMove: (idx: Int, x: Float, y: Float) -> Unit,
    onOutputTermMove: (idx: Int, x: Float, y: Float) -> Unit,
    onGateDelete: (String) -> Unit,
    onWireDelete: (String) -> Unit,
    onOutputMapDelete: (Int) -> Unit,
    dragGhostLineEnd: Offset?,
    onGhostLine: (Offset?) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    fun computeRects(): List<GateRect> = gates.map { g ->
        val def = ChipLibrary.get(g.chipId)
        val w = when {
            def.inputCount > 8 || def.outputCount > 8 -> 154f
            def.inputCount > 4 || def.outputCount > 4 -> 134f
            else -> 118f
        }
        val h = max(54f, 19f * max(def.inputCount, def.outputCount) + 28f)
        GateRect(g, g.x, g.y, w, h)
    }

    var rects by remember(gates) { mutableStateOf(computeRects()) }
    rects = computeRects()
    val rectById = rects.associateBy { it.chip.instanceId }
    val chipDefs = gates.associate { it.instanceId to ChipLibrary.get(it.chipId) }

    fun defaultInputPos(idx: Int) = Offset(32f, 78f + idx * 56f)
    fun defaultOutputPos(idx: Int) = Offset(920f, 78f + idx * 56f)

    val inputLayouts = level.inputs.mapIndexed { i, name ->
        val p = inputPositions[i]
        IoTerminalLayout(i, if (p != null) Offset(p.x, p.y) else defaultInputPos(i), name, true)
    }
    val outputLayouts = level.outputs.mapIndexed { i, name ->
        val p = outputPositions[i]
        IoTerminalLayout(i, if (p != null) Offset(p.x, p.y) else defaultOutputPos(i), name, false)
    }

    val latestRects by rememberUpdatedState(rects)
    val latestInputLayouts by rememberUpdatedState(inputLayouts)
    val latestOutputLayouts by rememberUpdatedState(outputLayouts)
    val latestWires by rememberUpdatedState(wires)
    val latestOutputMaps by rememberUpdatedState(outputMaps)
    val latestWiringFrom by rememberUpdatedState(wiringFrom)
    val latestChipDefs by rememberUpdatedState(chipDefs)
    val latestRectById by rememberUpdatedState(rectById)

    val currentOnCreateWire by rememberUpdatedState(onCreateWire)
    val currentOnStartWiring by rememberUpdatedState(onStartWiring)
    val currentOnCancelWiring by rememberUpdatedState(onCancelWiring)
    val currentOnGateMove by rememberUpdatedState(onGateMove)
    val currentOnInputMove by rememberUpdatedState(onInputTermMove)
    val currentOnOutputMove by rememberUpdatedState(onOutputTermMove)
    val currentOnGateDelete by rememberUpdatedState(onGateDelete)
    val currentOnWireDelete by rememberUpdatedState(onWireDelete)
    val currentOnOutputMapDelete by rememberUpdatedState(onOutputMapDelete)
    val currentOnGhostLine by rememberUpdatedState(onGhostLine)

    var dragMode by remember { mutableStateOf(DragMode.None) }
    var draggedGateId by remember { mutableStateOf<String?>(null) }
    var wiringStart by remember { mutableStateOf<HitOutput?>(null) }

    val pinHitR = 26f
    val termMoveHitR = 44f
    val termWireDotR = 18f

    fun pillW(name: String) = 70f + name.length * 3.6f
    fun dotForInput(t: IoTerminalLayout) = Offset(t.center.x + pillW(t.name) / 2f - 10f, t.center.y)
    fun dotForOutput(t: IoTerminalLayout) = Offset(t.center.x - pillW(t.name) / 2f + 10f, t.center.y)

    fun hitOutputDot(pos: Offset): HitOutput? {
        for (t in latestInputLayouts) if ((pos - dotForInput(t)).getDistance() < termWireDotR) return HitOutput(WireEnd("__IN_${t.index}", 0), dotForInput(t))
        for (gr in latestRects) {
            val def = latestChipDefs[gr.chip.instanceId] ?: continue
            for (j in 0 until def.outputCount) {
                val pp = gr.outputPos(j, def.outputCount)
                if ((pos - pp).getDistance() < pinHitR) return HitOutput(WireEnd(gr.chip.instanceId, j), pp)
            }
        }
        return null
    }
    fun hitInputDot(pos: Offset): HitInput? {
        for (gr in latestRects) {
            val def = latestChipDefs[gr.chip.instanceId] ?: continue
            for (j in 0 until def.inputCount) {
                val pp = gr.inputPos(j, def.inputCount)
                if ((pos - pp).getDistance() < pinHitR) return HitInput(WireEnd(gr.chip.instanceId, j), pp)
            }
        }
        for (t in latestOutputLayouts) if ((pos - dotForOutput(t)).getDistance() < termWireDotR) return HitInput(WireEnd("__OUT_${t.index}", 0), dotForOutput(t))
        return null
    }
    fun hitGateBody(pos: Offset): PlacedChip? = latestRects.reversed().firstOrNull { it.bodyContains(pos) }?.chip
    fun hitInputTermMove(pos: Offset): IoTerminalLayout? = latestInputLayouts.firstOrNull { (pos - it.center).getDistance() < termMoveHitR }
    fun hitOutputTermMove(pos: Offset): IoTerminalLayout? = latestOutputLayouts.firstOrNull { (pos - it.center).getDistance() < termMoveHitR }

    fun resolveSource(end: WireEnd): Offset? {
        if (end.instanceId.startsWith("__IN_")) {
            val idx = end.instanceId.removePrefix("__IN_").toIntOrNull() ?: return null
            return latestInputLayouts.find { it.index == idx }?.let { dotForInput(it) }
        }
        val gr = latestRectById[end.instanceId] ?: return null
        val def = latestChipDefs[end.instanceId] ?: return null
        return gr.outputPos(end.pinIndex.coerceIn(0, max(0, def.outputCount - 1)), def.outputCount)
    }
    fun resolveSink(end: WireEnd): Offset? {
        if (end.instanceId.startsWith("__OUT_")) {
            val idx = end.instanceId.removePrefix("__OUT_").toIntOrNull() ?: return null
            return latestOutputLayouts.find { it.index == idx }?.let { dotForOutput(it) }
        }
        val gr = latestRectById[end.instanceId] ?: return null
        val def = latestChipDefs[end.instanceId] ?: return null
        return gr.inputPos(end.pinIndex.coerceIn(0, max(0, def.inputCount - 1)), def.inputCount)
    }
    fun findClosestWire(pos: Offset): Wire? {
        var best: Wire? = null
        var bestD = 28f
        for (w in latestWires) {
            val a = resolveSource(w.from) ?: continue
            val b = resolveSink(w.to) ?: continue
            val d = distPointToBezier(pos, a, b)
            if (d < bestD) { bestD = d; best = w }
        }
        return best
    }
    fun findClosestOutputMap(pos: Offset): OutputMapping? {
        var best: OutputMapping? = null
        var bestD = 28f
        for (om in latestOutputMaps) {
            val a = resolveSource(om.from) ?: continue
            val b = latestOutputLayouts.find { it.index == om.outputIndex }?.let { dotForOutput(it) } ?: continue
            val d = distPointToBezier(pos, a, b)
            if (d < bestD) { bestD = d; best = om }
        }
        return best
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C141D))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPos = down.position
                        var currentPos = downPos
                        var totalDrag = Offset.Zero
                        var isDragStarted = false
                        var mode = DragMode.None
                        var gateId: String? = null
                        var gateStartLeft = 0f
                        var gateStartTop = 0f
                        var inputIdx: Int? = null
                        var outputIdx: Int? = null
                        var inputStart = Offset.Zero
                        var outputStart = Offset.Zero
                        var wireStart: HitOutput? = null

                        // At down, check what's underneath – dot hits take priority for wiring
                        val outDotAtDown = hitOutputDot(downPos)
                        val inDotAtDown = hitInputDot(downPos)
                        val gateAtDown = if (outDotAtDown == null && inDotAtDown == null) hitGateBody(downPos) else null
                        val inTermMoveAtDown = if (outDotAtDown == null && gateAtDown == null) hitInputTermMove(downPos) else null
                        val outTermMoveAtDown = if (outDotAtDown == null && gateAtDown == null && inTermMoveAtDown == null) hitOutputTermMove(downPos) else null
                        val downTime = System.currentTimeMillis()
                        var longPressHandled = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val main = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (main.changedToUpIgnoreConsumed()) {
                                if (!isDragStarted && !longPressHandled) {
                                    // Tap handling
                                    if (latestWiringFrom != null || wiringStart != null) {
                                        val dst = hitInputDot(main.position)
                                        if (dst != null) {
                                            val from = latestWiringFrom ?: wiringStart?.end
                                            if (from != null && from.instanceId != dst.end.instanceId) currentOnCreateWire(from, dst.end)
                                        }
                                        currentOnCancelWiring()
                                        wiringStart = null
                                        dragMode = DragMode.None
                                        currentOnGhostLine(null)
                                    } else {
                                        val outHit = hitOutputDot(main.position)
                                        if (outHit != null) {
                                            wiringStart = outHit
                                            dragMode = DragMode.Wire
                                            currentOnStartWiring(outHit.end)
                                            currentOnGhostLine(outHit.pos)
                                        } else {
                                            val wire = findClosestWire(main.position)
                                            if (wire != null) currentOnWireDelete(wire.id)
                                            else {
                                                val om = findClosestOutputMap(main.position)
                                                if (om != null) currentOnOutputMapDelete(om.outputIndex)
                                            }
                                        }
                                    }
                                } else if (isDragStarted && mode == DragMode.Wire) {
                                    val dst = hitInputDot(currentPos)
                                    if (dst != null && wireStart != null) {
                                        val from = wireStart.end
                                        if (from.instanceId != dst.end.instanceId) currentOnCreateWire(from, dst.end)
                                    }
                                    currentOnGhostLine(null)
                                    currentOnCancelWiring()
                                    wiringStart = null
                                    dragMode = DragMode.None
                                    draggedGateId = null
                                } else if (isDragStarted) {
                                    dragMode = DragMode.None
                                    draggedGateId = null
                                }
                                break
                            }

                            if (longPressHandled) continue
                            val pos = main.position
                            totalDrag = pos - downPos

                            if (!isDragStarted) {
                                val elapsed = System.currentTimeMillis() - downTime
                                if (elapsed > 460 && totalDrag.getDistance() < 14f) {
                                    if (gateAtDown != null) currentOnGateDelete(gateAtDown.instanceId)
                                    longPressHandled = true
                                    break
                                }
                                if (totalDrag.getDistance() > 14f) {
                                    isDragStarted = true
                                    when {
                                        outDotAtDown != null -> {
                                            mode = DragMode.Wire
                                            wireStart = outDotAtDown
                                            wiringStart = outDotAtDown
                                            dragMode = DragMode.Wire
                                            currentOnStartWiring(outDotAtDown.end)
                                            currentOnGhostLine(outDotAtDown.pos)
                                        }
                                        inTermMoveAtDown != null -> {
                                            mode = DragMode.InputTerm
                                            inputIdx = inTermMoveAtDown.index
                                            inputStart = inTermMoveAtDown.center
                                            dragMode = DragMode.InputTerm
                                        }
                                        outTermMoveAtDown != null -> {
                                            mode = DragMode.OutputTerm
                                            outputIdx = outTermMoveAtDown.index
                                            outputStart = outTermMoveAtDown.center
                                            dragMode = DragMode.OutputTerm
                                        }
                                        gateAtDown != null -> {
                                            mode = DragMode.Gate
                                            gateId = gateAtDown.instanceId
                                            val gr = latestRects.find { it.chip.instanceId == gateId }
                                            if (gr != null) {
                                                gateStartLeft = gr.left
                                                gateStartTop = gr.top
                                            }
                                            draggedGateId = gateAtDown.instanceId
                                            dragMode = DragMode.Gate
                                        }
                                        else -> mode = DragMode.None
                                    }
                                }
                            }

                            if (isDragStarted) {
                                when (mode) {
                                    DragMode.Gate -> {
                                        val gid = gateId ?: continue
                                        // Free placement anywhere – no grid, no clamp
                                        currentOnGateMove(gid, gateStartLeft + totalDrag.x, gateStartTop + totalDrag.y)
                                    }
                                    DragMode.InputTerm -> {
                                        val idx = inputIdx ?: continue
                                        currentOnInputMove(idx, inputStart.x + totalDrag.x, inputStart.y + totalDrag.y)
                                    }
                                    DragMode.OutputTerm -> {
                                        val idx = outputIdx ?: continue
                                        currentOnOutputMove(idx, outputStart.x + totalDrag.x, outputStart.y + totalDrag.y)
                                    }
                                    DragMode.Wire -> currentOnGhostLine(pos)
                                    DragMode.None -> {}
                                }
                            }
                            currentPos = pos
                        }

                        if (dragMode == DragMode.Gate || dragMode == DragMode.InputTerm || dragMode == DragMode.OutputTerm) {
                            draggedGateId = null
                            dragMode = DragMode.None
                        }
                    }
                }
            }
    ) {
        // No grid per user request – plain background
        drawRect(Color(0xFF0C141D))
        for (w in wires) {
            val a = resolveSource(w.from) ?: continue
            val b = resolveSink(w.to) ?: continue
            drawWire(a, b, Color(0xFF7ED8B6), false)
        }
        for (om in outputMaps) {
            val a = resolveSource(om.from) ?: continue
            val b = outputLayouts.find { it.index == om.outputIndex }?.let { dotForOutput(it) } ?: continue
            drawWire(a, b, Color(0xFFFDE68A), false, outToFinal = true)
        }
        val ghostStart = wiringStart?.pos ?: wiringFrom?.let { resolveSource(it) }
        val ghostEnd = dragGhostLineEnd
        if (ghostStart != null && ghostEnd != null) {
            val overValid = hitInputDot(ghostEnd) != null
            drawWire(ghostStart, ghostEnd, if (overValid) Color(0xFFFFFF00) else Color(0x66FFFFFF), true, dash = true)
        } else if (wiringFrom != null) {
            resolveSource(wiringFrom)?.let { drawCircle(Color.Yellow.copy(alpha = 0.30f), 24f, it) }
        }
        for (t in inputLayouts) {
            val isSrc = wiringFrom?.instanceId == "__IN_${t.index}" || wiringStart?.end?.instanceId == "__IN_${t.index}"
            drawTerminalPill(t.center, t.name, if (isSrc) Color(0xFF3A3110) else Color(0xFF17283A), if (isSrc) Color.Yellow else Color(0xFF38BDF8), true, isSrc, textMeasurer)
        }
        for (t in outputLayouts) {
            val isTargeting = dragGhostLineEnd?.let { (it - dotForOutput(t)).getDistance() < termWireDotR + 8f } ?: false
            drawTerminalPill(t.center, t.name, if (isTargeting) Color(0xFF3A1E1E) else Color(0xFF221515), if (isTargeting) Color.Yellow else Color(0xFFF87171), false, isTargeting, textMeasurer)
        }
        for (gr in rects) {
            val def = chipDefs[gr.chip.instanceId] ?: continue
            val isDragged = draggedGateId == gr.chip.instanceId
            val base = when (def.category) {
                ChipCategory.PRIMITIVE -> Color(0xFF1E3A4A)
                ChipCategory.FOUNDATION -> Color(0xFF1C5A55)
                ChipCategory.ROUTING -> Color(0xFF7A5E1E)
                ChipCategory.ARITH -> Color(0xFF8A4A26)
                ChipCategory.MEMORY -> Color(0xFF4A2E7A)
                ChipCategory.CPU -> Color(0xFF8A2038)
            }
            val corner = 12f
            drawRoundRect(Color.Black.copy(alpha = 0.32f), Offset(gr.left + 2f, gr.top + 5f), Size(gr.width, gr.height), CornerRadius(corner, corner))
            drawRoundRect(if (isDragged) base.copy(alpha = 0.98f) else base, Offset(gr.left, gr.top), Size(gr.width, gr.height), CornerRadius(corner, corner))
            drawRoundRect(if (isDragged) Color.White else Color.White.copy(alpha = 0.16f), Offset(gr.left, gr.top), Size(gr.width, gr.height), CornerRadius(corner, corner), style = Stroke(width = if (isDragged) 2.4f else 1f))
            drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(gr.left, gr.top), Size(gr.width, 16f), CornerRadius(corner, corner))
            drawText(textMeasurer, def.displayName, Offset(gr.left + 9f, gr.top + 7f), TextStyle(if (def.category == ChipCategory.ROUTING) Color(0xFFFFE8A0) else Color.White, 11.sp))
            drawText(textMeasurer, "${def.nandCost}N", Offset(gr.left + gr.width - 30f, gr.top + 7f), TextStyle(Color.White.copy(alpha = 0.52f), 9.sp))
            for (j in 0 until def.inputCount) {
                val pp = gr.inputPos(j, def.inputCount)
                val hov = dragGhostLineEnd?.let { (it - pp).getDistance() < pinHitR } ?: false
                drawCircle(Color.Black.copy(alpha = 0.55f), 7.5f, pp + Offset(0f, 1f))
                drawCircle(if (hov) Color.Yellow else Color.White, 6.2f, pp)
                drawCircle(if (hov) Color.Yellow else Color(0xFF94A3B8), 2.3f, pp)
                if (def.inputCount <= 6) drawText(textMeasurer, def.inputs[j], pp + Offset(10f, -6f), TextStyle(Color.White.copy(alpha = 0.82f), 8.sp))
            }
            for (j in 0 until def.outputCount) {
                val pp = gr.outputPos(j, def.outputCount)
                val isSrc = (wiringFrom?.instanceId == gr.chip.instanceId && wiringFrom.pinIndex == j) || (wiringStart?.end?.instanceId == gr.chip.instanceId && wiringStart?.end?.pinIndex == j)
                drawCircle(Color.Black.copy(alpha = 0.55f), 7.5f, pp + Offset(0f, 1f))
                drawCircle(if (isSrc) Color.Yellow else Color(0xFFA7F3D0), 6.8f, pp)
                drawCircle(if (isSrc) Color.Yellow else Color(0xFF22C55E), 2.8f, pp)
                if (isSrc) drawCircle(Color.Yellow.copy(alpha = 0.22f), 16f, pp)
                if (def.outputCount <= 6) {
                    val lbl = def.outputs[j]
                    drawText(textMeasurer, lbl, pp + Offset(-12f - lbl.length * 5.3f, -6f), TextStyle(Color.White.copy(alpha = 0.82f), 8.sp))
                }
            }
        }
    }
}

private fun DrawScope.drawTerminalPill(center: Offset, name: String, bg: Color, border: Color, isInput: Boolean, highlight: Boolean, textMeasurer: androidx.compose.ui.text.TextMeasurer) {
    val w = 70f + name.length * 3.6f
    val h = 30f
    val lt = Offset(center.x - w / 2f, center.y - h / 2f)
    drawRoundRect(bg, lt, Size(w, h), CornerRadius(h / 2f, h / 2f))
    drawRoundRect(border, lt, Size(w, h), CornerRadius(h / 2f, h / 2f), style = Stroke(width = if (highlight) 2.3f else 1.2f))
    val dot = if (isInput) Offset(lt.x + w - 10f, center.y) else Offset(lt.x + 10f, center.y)
    val dotCol = if (isInput) Color(0xFF38BDF8) else Color(0xFFF87171)
    drawCircle(Color.Black.copy(alpha = 0.55f), 7f, dot + Offset(0f, 1f))
    drawCircle(if (highlight) Color.Yellow else dotCol, 6f, dot)
    val txtX = if (isInput) lt.x + 10f else lt.x + 22f
    drawText(textMeasurer, name, Offset(txtX, center.y - 7.5f), TextStyle(Color.White, 10.5.sp))
    if (highlight) drawCircle(Color.Yellow.copy(alpha = 0.18f), 28f, center)
}

private fun DrawScope.drawWire(from: Offset, to: Offset, color: Color, isSelected: Boolean, dash: Boolean = false, outToFinal: Boolean = false) {
    val dx = to.x - from.x
    val ctrl = min(180f, max(50f, kotlin.math.abs(dx) * 0.58f))
    val path = Path().apply {
        moveTo(from.x, from.y)
        cubicTo(from.x + ctrl, from.y, to.x - ctrl, to.y, to.x, to.y)
    }
    if (isSelected || color.alpha > 0.5f) drawPath(path, color.copy(alpha = 0.16f), style = Stroke(width = 8f))
    drawPath(path, color, style = Stroke(width = if (isSelected) 3.4f else 2.7f))
    drawCircle(color, 3.6f, to)
    drawCircle(Color.Black.copy(alpha = 0.7f), 1.3f, to)
}

private fun distPointToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ap = p - a
    val ab = b - a
    val ab2 = ab.x * ab.x + ab.y * ab.y
    if (ab2 == 0f) return (p - a).getDistance()
    var t = (ap.x * ab.x + ap.y * ab.y) / ab2
    t = t.coerceIn(0f, 1f)
    val proj = Offset(a.x + ab.x * t, a.y + ab.y * t)
    return (p - proj).getDistance()
}
private fun distPointToBezier(p: Offset, from: Offset, to: Offset): Float {
    val dx = to.x - from.x
    val ctrl = min(180f, max(50f, kotlin.math.abs(dx) * 0.58f))
    var best = Float.MAX_VALUE
    var prev = from
    val steps = 18
    for (i in 1..steps) {
        val t = i / steps.toFloat()
        val mt = 1 - t
        val x = mt * mt * mt * from.x + 3 * mt * mt * t * (from.x + ctrl) + 3 * mt * t * t * (to.x - ctrl) + t * t * t * to.x
        val y = mt * mt * mt * from.y + 3 * mt * mt * t * from.y + 3 * mt * t * t * to.y + t * t * t * to.y
        val cur = Offset(x, y)
        val d = distPointToSegment(p, prev, cur)
        if (d < best) best = d
        prev = cur
        if (best < 2f) return best
    }
    return best
}
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitFirstDown(requireUnconsumed: Boolean = true): androidx.compose.ui.input.pointer.PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
            val down = event.changes.firstOrNull { if (requireUnconsumed) !it.isConsumed else true } ?: continue
            return down
        }
    }
}
