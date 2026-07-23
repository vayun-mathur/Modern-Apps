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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.data.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class GateBox(val chip: PlacedChip, val left: Float, val top: Float, val w: Float, val h: Float) {
    fun inputPos(i: Int, count: Int): Offset {
        if (count <= 1) return Offset(left, top + h / 2f)
        val gap = h / (count + 1)
        return Offset(left, top + gap * (i + 1))
    }
    fun outputPos(i: Int, count: Int): Offset {
        if (count <= 1) return Offset(left + w, top + h / 2f)
        val gap = h / (count + 1)
        return Offset(left + w, top + gap * (i + 1))
    }
    fun bodyContains(p: Offset): Boolean = p.x in left..left + w && p.y in top..top + h
}

data class TerminalBox(val idx: Int, val center: Offset, val name: String, val isInput: Boolean, val pillW: Float)
data class HitOutput(val end: WireEnd, val pos: Offset)
data class HitInput(val end: WireEnd, val pos: Offset)

sealed class DragMode {
    object None : DragMode()
    data class Gate(val id: String, val grabOffset: Offset, val start: Offset, val startLeft: Float, val startTop: Float) : DragMode()
    data class InputTerm(val idx: Int, val start: Offset) : DragMode()
    data class OutputTerm(val idx: Int, val start: Offset) : DragMode()
    data class Wiring(val from: WireEnd, val fromPos: Offset, var cur: Offset) : DragMode()
}

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
    onGateMoveFinished: (id: String, x: Float, y: Float) -> Unit,
    onInputTermMove: (idx: Int, x: Float, y: Float) -> Unit,
    onInputTermMoveFinished: (idx: Int, x: Float, y: Float) -> Unit,
    onOutputTermMove: (idx: Int, x: Float, y: Float) -> Unit,
    onOutputTermMoveFinished: (idx: Int, x: Float, y: Float) -> Unit,
    onGateDelete: (String) -> Unit,
    onWireDelete: (String) -> Unit,
    onOutputMapDelete: (Int) -> Unit,
    dragGhostLineEnd: Offset?,
    onGhostLine: (Offset?) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()
    var canvasSizePx by remember { mutableStateOf(Size.Zero) }

    val pinHitR = with(density) { 24.dp.toPx() }
    val termMoveHitR = with(density) { 40.dp.toPx() }
    val termWireDotR = with(density) { 20.dp.toPx() }
    val wireHitThreshold = with(density) { 32.dp.toPx() }

    fun gateSizeFor(def: ChipDef): Pair<Float, Float> {
        val maxPins = max(def.inputCount, def.outputCount)
        val wDp = when { maxPins > 8 -> 154.dp; maxPins > 4 -> 134.dp; else -> 118.dp }
        val hDp = if (maxPins <= 2) 54.dp else (19.dp * maxPins + 28.dp).coerceAtLeast(54.dp)
        return with(density) { wDp.toPx() to hDp.toPx() }
    }

    val gateBoxes: List<GateBox> = remember(gates) {
        gates.map { g ->
            val def = ChipLibrary.get(g.chipId)
            val (w, h) = gateSizeFor(def)
            GateBox(g, g.x, g.y, w, h)
        }
    }
    val rectById = remember(gateBoxes) { gateBoxes.associateBy { it.chip.instanceId } }
    val chipDefs = remember(gates) { gates.associate { it.instanceId to ChipLibrary.get(it.chipId) } }

    fun pillW(name: String): Float = with(density) { 72.dp.toPx() + name.length * 3.6f }

    val inputLayouts: List<TerminalBox> = remember(level.inputs, inputPositions, canvasSizePx) {
        level.inputs.mapIndexed { i, name ->
            val default = defaultInputPos(i, canvasSizePx, pillW(name))
            val p = inputPositions[i]
            val center = if (p != null) Offset(p.x, p.y) else default
            TerminalBox(i, center, name, true, pillW(name))
        }
    }
    val outputLayouts: List<TerminalBox> = remember(level.outputs, outputPositions, canvasSizePx) {
        level.outputs.mapIndexed { i, name ->
            val default = defaultOutputPos(i, canvasSizePx, pillW(name))
            val p = outputPositions[i]
            val center = if (p != null) Offset(p.x, p.y) else default
            TerminalBox(i, center, name, false, pillW(name))
        }
    }

    fun dotForInput(t: TerminalBox): Offset = Offset(t.center.x + t.pillW / 2f - with(density) { 10.dp.toPx() }, t.center.y)
    fun dotForOutput(t: TerminalBox): Offset = Offset(t.center.x - t.pillW / 2f + with(density) { 10.dp.toPx() }, t.center.y)

    fun resolveSource(end: WireEnd): Offset? {
        if (end.instanceId.startsWith("__IN_")) {
            val idx = end.instanceId.removePrefix("__IN_").toIntOrNull() ?: return null
            return inputLayouts.find { it.idx == idx }?.let { dotForInput(it) }
        }
        val gr = rectById[end.instanceId] ?: return null
        val def = chipDefs[end.instanceId] ?: return null
        return gr.outputPos(end.pinIndex.coerceIn(0, max(0, def.outputCount - 1)), def.outputCount)
    }
    fun resolveSink(end: WireEnd): Offset? {
        if (end.instanceId.startsWith("__OUT_")) {
            val idx = end.instanceId.removePrefix("__OUT_").toIntOrNull() ?: return null
            return outputLayouts.find { it.idx == idx }?.let { dotForOutput(it) }
        }
        val gr = rectById[end.instanceId] ?: return null
        val def = chipDefs[end.instanceId] ?: return null
        return gr.inputPos(end.pinIndex.coerceIn(0, max(0, def.inputCount - 1)), def.inputCount)
    }

    fun hitOutputDot(pos: Offset): HitOutput? {
        for (t in inputLayouts) if ((pos - dotForInput(t)).getDistance() < termWireDotR) return HitOutput(WireEnd("__IN_${t.idx}", 0), dotForInput(t))
        for (box in gateBoxes) {
            val def = chipDefs[box.chip.instanceId] ?: continue
            for (j in 0 until def.outputCount) {
                val pp = box.outputPos(j, def.outputCount)
                if ((pos - pp).getDistance() < pinHitR) return HitOutput(WireEnd(box.chip.instanceId, j), pp)
            }
        }
        return null
    }
    fun hitInputDot(pos: Offset): HitInput? {
        for (box in gateBoxes) {
            val def = chipDefs[box.chip.instanceId] ?: continue
            for (j in 0 until def.inputCount) {
                val pp = box.inputPos(j, def.inputCount)
                if ((pos - pp).getDistance() < pinHitR) return HitInput(WireEnd(box.chip.instanceId, j), pp)
            }
        }
        for (t in outputLayouts) if ((pos - dotForOutput(t)).getDistance() < termWireDotR) return HitInput(WireEnd("__OUT_${t.idx}", 0), dotForOutput(t))
        return null
    }
    fun hitGateBody(pos: Offset): GateBox? = gateBoxes.reversed().firstOrNull { it.bodyContains(pos) }
    fun hitInputTermMove(pos: Offset): TerminalBox? = inputLayouts.firstOrNull { (pos - it.center).getDistance() < termMoveHitR }
    fun hitOutputTermMove(pos: Offset): TerminalBox? = outputLayouts.firstOrNull { (pos - it.center).getDistance() < termMoveHitR }

    fun findClosestWire(pos: Offset): Wire? {
        var best: Wire? = null; var bestD = wireHitThreshold
        for (w in wires) {
            val a = resolveSource(w.from) ?: continue; val b = resolveSink(w.to) ?: continue
            val d = distPointToBezier(pos, a, b)
            if (d < bestD) { bestD = d; best = w }
        }
        return best
    }
    fun findClosestOutputMap(pos: Offset): OutputMapping? {
        var best: OutputMapping? = null; var bestD = wireHitThreshold
        for (om in outputMaps) {
            val a = resolveSource(om.from) ?: continue
            val b = outputLayouts.find { it.idx == om.outputIndex }?.let { dotForOutput(it) } ?: continue
            val d = distPointToBezier(pos, a, b)
            if (d < bestD) { bestD = d; best = om }
        }
        return best
    }

    var draggedGateId by remember { mutableStateOf<String?>(null) }
    var wiringStartLocal by remember { mutableStateOf<HitOutput?>(null) }

    val currentOnCreateWire by rememberUpdatedState(onCreateWire)
    val currentOnStartWiring by rememberUpdatedState(onStartWiring)
    val currentOnCancelWiring by rememberUpdatedState(onCancelWiring)
    val currentOnGateMove by rememberUpdatedState(onGateMove)
    val currentOnGateMoveFinished by rememberUpdatedState(onGateMoveFinished)
    val currentOnInputMove by rememberUpdatedState(onInputTermMove)
    val currentOnInputMoveFinished by rememberUpdatedState(onInputTermMoveFinished)
    val currentOnOutputMove by rememberUpdatedState(onOutputTermMove)
    val currentOnOutputMoveFinished by rememberUpdatedState(onOutputTermMoveFinished)
    val currentOnGateDelete by rememberUpdatedState(onGateDelete)
    val currentOnWireDelete by rememberUpdatedState(onWireDelete)
    val currentOnOutputMapDelete by rememberUpdatedState(onOutputMapDelete)
    val currentOnGhostLine by rememberUpdatedState(onGhostLine)
    val latestWiringFrom by rememberUpdatedState(wiringFrom)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C141D))
            .onSizeChanged { canvasSizePx = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(gates, wires, inputPositions, outputPositions, canvasSizePx) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPos = down.position
                        var currentPos = downPos
                        var totalDrag = Offset.Zero
                        var isDragStarted = false
                        var mode: DragMode = DragMode.None
                        var gateStartLeft = 0f
                        var gateStartTop = 0f
                        var inputStart = Offset.Zero
                        var outputStart = Offset.Zero
                        var wireStart: HitOutput? = null

                        val outDotAtDown = hitOutputDot(downPos)
                        val inDotAtDown = hitInputDot(downPos)
                        val gateBoxAtDown = if (outDotAtDown == null && inDotAtDown == null) hitGateBody(downPos) else null
                        val gateAtDown = gateBoxAtDown?.chip
                        val inTermMoveAtDown = if (outDotAtDown == null && gateAtDown == null) hitInputTermMove(downPos) else null
                        val outTermMoveAtDown = if (outDotAtDown == null && gateAtDown == null && inTermMoveAtDown == null) hitOutputTermMove(downPos) else null
                        val downTime = System.currentTimeMillis()
                        var longPressHandled = false

                        if (outDotAtDown != null) {
                            wireStart = outDotAtDown
                            wiringStartLocal = outDotAtDown
                            mode = DragMode.Wiring(outDotAtDown.end, outDotAtDown.pos, outDotAtDown.pos)
                            currentOnStartWiring(outDotAtDown.end)
                            currentOnGhostLine(outDotAtDown.pos)
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val main = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (main.changedToUpIgnoreConsumed()) {
                                if (!isDragStarted && !longPressHandled) {
                                    if (latestWiringFrom != null || wiringStartLocal != null) {
                                        val dst = hitInputDot(main.position)
                                        if (dst != null) {
                                            val from = latestWiringFrom ?: wiringStartLocal?.end
                                            if (from != null && from.instanceId != dst.end.instanceId) currentOnCreateWire(from, dst.end)
                                        }
                                        currentOnCancelWiring(); wiringStartLocal = null; currentOnGhostLine(null)
                                    } else {
                                        val outHit = hitOutputDot(main.position)
                                        if (outHit != null) { wiringStartLocal = outHit; currentOnStartWiring(outHit.end); currentOnGhostLine(outHit.pos) }
                                        else {
                                            val wire = findClosestWire(main.position)
                                            if (wire != null) currentOnWireDelete(wire.id)
                                            else {
                                                val om = findClosestOutputMap(main.position)
                                                if (om != null) currentOnOutputMapDelete(om.outputIndex)
                                            }
                                        }
                                    }
                                } else if (isDragStarted && mode is DragMode.Wiring) {
                                    val dst = hitInputDot(currentPos)
                                    if (dst != null && wireStart != null) {
                                        val from = wireStart.end
                                        if (from.instanceId != dst.end.instanceId) currentOnCreateWire(from, dst.end)
                                    }
                                    currentOnGhostLine(null); currentOnCancelWiring(); wiringStartLocal = null; draggedGateId = null
                                } else if (isDragStarted) {
                                    when (mode) {
                                        is DragMode.Gate -> {
                                            val gid = (mode as DragMode.Gate).id
                                            val box = rectById[gid]
                                            if (box != null) {
                                                val clamped = clampGate(Offset(box.left, box.top), box.w, box.h, canvasSizePx)
                                                currentOnGateMoveFinished(gid, clamped.x, clamped.y)
                                            }
                                        }
                                        is DragMode.InputTerm -> {
                                            val idx = (mode as DragMode.InputTerm).idx
                                            val term = inputLayouts.find { it.idx == idx }
                                            term?.let {
                                                val clamped = clampTerm(it.center, it.pillW, canvasSizePx, 8f)
                                                currentOnInputMoveFinished(idx, clamped.x, clamped.y)
                                            }
                                        }
                                        is DragMode.OutputTerm -> {
                                            val idx = (mode as DragMode.OutputTerm).idx
                                            val term = outputLayouts.find { it.idx == idx }
                                            term?.let {
                                                val clamped = clampTerm(it.center, it.pillW, canvasSizePx, 8f)
                                                currentOnOutputMoveFinished(idx, clamped.x, clamped.y)
                                            }
                                        }
                                        else -> {}
                                    }
                                    draggedGateId = null
                                }
                                break
                            }
                            if (longPressHandled) continue
                            val pos = main.position
                            totalDrag = pos - downPos
                            if (!isDragStarted) {
                                val elapsed = System.currentTimeMillis() - downTime
                                if (mode is DragMode.None && gateAtDown != null && elapsed > 500 && totalDrag.getDistance() < 12f) {
                                    try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                    currentOnGateDelete(gateAtDown.instanceId); longPressHandled = true; break
                                }
                                if (totalDrag.getDistance() > 12f) {
                                    if (mode is DragMode.Wiring) isDragStarted = true else {
                                        isDragStarted = true
                                        when {
                                            outDotAtDown != null -> {
                                                mode = DragMode.Wiring(outDotAtDown.end, outDotAtDown.pos, pos)
                                                wireStart = outDotAtDown; wiringStartLocal = outDotAtDown
                                                if (latestWiringFrom == null) currentOnStartWiring(outDotAtDown.end)
                                                currentOnGhostLine(pos)
                                            }
                                            inTermMoveAtDown != null -> { mode = DragMode.InputTerm(inTermMoveAtDown.idx, inTermMoveAtDown.center); inputStart = inTermMoveAtDown.center }
                                            outTermMoveAtDown != null -> { mode = DragMode.OutputTerm(outTermMoveAtDown.idx, outTermMoveAtDown.center); outputStart = outTermMoveAtDown.center }
                                            gateAtDown != null && gateBoxAtDown != null -> {
                                                val grabOff = downPos - Offset(gateBoxAtDown.left, gateBoxAtDown.top)
                                                mode = DragMode.Gate(gateAtDown.instanceId, grabOff, Offset(gateBoxAtDown.left, gateBoxAtDown.top), gateBoxAtDown.left, gateBoxAtDown.top)
                                                gateStartLeft = gateBoxAtDown.left; gateStartTop = gateBoxAtDown.top; draggedGateId = gateAtDown.instanceId
                                            }
                                            else -> mode = DragMode.None
                                        }
                                    }
                                }
                            }
                            if (isDragStarted) {
                                when (mode) {
                                    is DragMode.Gate -> {
                                        val gMode = mode as DragMode.Gate
                                        val newLeft = gMode.startLeft + totalDrag.x
                                        val newTop = gMode.startTop + totalDrag.y
                                        val box = rectById[gMode.id]
                                        val clamped = if (box != null) clampGate(Offset(newLeft, newTop), box.w, box.h, canvasSizePx) else Offset(newLeft.coerceAtLeast(0f), newTop.coerceAtLeast(0f))
                                        currentOnGateMove(gMode.id, clamped.x, clamped.y)
                                    }
                                    is DragMode.InputTerm -> {
                                        val idx = (mode as DragMode.InputTerm).idx
                                        val newCenter = inputStart + totalDrag
                                        val term = inputLayouts.find { it.idx == idx }
                                        val clamped = if (term != null) clampTerm(newCenter, term.pillW, canvasSizePx, 8f) else newCenter
                                        currentOnInputMove(idx, clamped.x, clamped.y)
                                    }
                                    is DragMode.OutputTerm -> {
                                        val idx = (mode as DragMode.OutputTerm).idx
                                        val newCenter = outputStart + totalDrag
                                        val term = outputLayouts.find { it.idx == idx }
                                        val clamped = if (term != null) clampTerm(newCenter, term.pillW, canvasSizePx, 8f) else newCenter
                                        currentOnOutputMove(idx, clamped.x, clamped.y)
                                    }
                                    is DragMode.Wiring -> { (mode as DragMode.Wiring).cur = pos; currentOnGhostLine(pos) }
                                    else -> {}
                                }
                            }
                            currentPos = pos
                        }
                        if (mode is DragMode.Gate) draggedGateId = null
                    }
                }
            }
    ) {
        drawRect(Color(0xFF0C141D))
        val gridSpacing = 36f
        if (canvasSizePx.width > 0) {
            var gx = 0f
            while (gx < canvasSizePx.width) {
                var gy = 0f
                while (gy < canvasSizePx.height) { drawCircle(Color.White.copy(alpha = 0.05f), 1.2f, Offset(gx, gy)); gy += gridSpacing }
                gx += gridSpacing
            }
        }
        for (w in wires) {
            val a = resolveSource(w.from) ?: continue; val b = resolveSink(w.to) ?: continue
            val (color, thick) = wireStyleForWidth(w.busWidth); drawWire(a, b, color, false, thickPx = thick)
        }
        for (om in outputMaps) {
            val a = resolveSource(om.from) ?: continue
            val b = outputLayouts.find { it.idx == om.outputIndex }?.let { dotForOutput(it) } ?: continue
            val srcWidth = try {
                val gate = gateBoxes.find { it.chip.instanceId == om.from.instanceId }
                val def = gate?.let { chipDefs[it.chip.instanceId] }
                def?.outputPinWidth(om.from.pinIndex) ?: 1
            } catch (_: Exception) { 1 }
            val (_, thick) = wireStyleForWidth(srcWidth); drawWire(a, b, Color(0xFFFDE68A), false, thickPx = thick)
        }
        val ghostStart = wiringStartLocal?.pos ?: wiringFrom?.let { resolveSource(it) }
        val ghostEnd = dragGhostLineEnd
        if (ghostStart != null && ghostEnd != null) {
            val overValid = hitInputDot(ghostEnd) != null
            val ghostColor = if (overValid) Color(0xFFFFFF00) else Color(0x66FFFFFF)
            drawWire(ghostStart, ghostEnd, ghostColor, true, thickPx = 3.2f, dash = true)
        } else if (wiringFrom != null) {
            resolveSource(wiringFrom)?.let { drawCircle(Color.Yellow.copy(alpha = 0.30f), 24f, it) }
        }
        for (t in inputLayouts) {
            val isSrc = wiringFrom?.instanceId == "__IN_${t.idx}" || wiringStartLocal?.end?.instanceId == "__IN_${t.idx}"
            drawTerminalPill(t.center, t.name, if (isSrc) Color(0xFF3A3110) else Color(0xFF17283A), if (isSrc) Color.Yellow else Color(0xFF38BDF8), true, isSrc, textMeasurer)
        }
        for (t in outputLayouts) {
            val isTargeting = dragGhostLineEnd?.let { (it - dotForOutput(t)).getDistance() < termWireDotR + 8f } ?: false
            drawTerminalPill(t.center, t.name, if (isTargeting) Color(0xFF3A1E1E) else Color(0xFF221515), if (isTargeting) Color.Yellow else Color(0xFFF87171), false, isTargeting, textMeasurer)
        }
        for (box in gateBoxes) {
            val def = chipDefs[box.chip.instanceId] ?: continue
            val isDragged = draggedGateId == box.chip.instanceId
            val base = when (def.category) {
                ChipCategory.PRIMITIVE -> Color(0xFF1E3A4A)
                ChipCategory.FOUNDATION -> Color(0xFF14532D)
                ChipCategory.ROUTING -> Color(0xFF713F12)
                ChipCategory.BUS -> Color(0xFF3B2E4A)
                ChipCategory.ARITH -> Color(0xFF7C2D12)
                ChipCategory.MEMORY -> Color(0xFF3B0764)
                ChipCategory.CPU -> Color(0xFF881337)
            }
            val corner = 12f
            drawRoundRect(Color.Black.copy(alpha = 0.32f), Offset(box.left + 2f, box.top + 5f), Size(box.w, box.h), CornerRadius(corner, corner))
            drawRoundRect(if (isDragged) base.copy(alpha = 0.98f) else base, Offset(box.left, box.top), Size(box.w, box.h), CornerRadius(corner, corner))
            drawRoundRect(if (isDragged) Color.White else Color.White.copy(alpha = 0.16f), Offset(box.left, box.top), Size(box.w, box.h), CornerRadius(corner, corner), style = Stroke(width = if (isDragged) 2.4f else 1f))
            drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(box.left, box.top), Size(box.w, 16f), CornerRadius(corner, corner))
            drawText(textMeasurer, def.displayName, Offset(box.left + 9f, box.top + 7f), TextStyle(if (def.category == ChipCategory.ROUTING) Color(0xFFFFE8A0) else Color.White, 11.sp))
            drawText(textMeasurer, "${def.nandCost}N", Offset(box.left + box.w - 30f, box.top + 7f), TextStyle(Color.White.copy(alpha = 0.52f), 9.sp))
            for (j in 0 until def.inputCount) {
                val pp = box.inputPos(j, def.inputCount)
                val hov = dragGhostLineEnd?.let { (it - pp).getDistance() < pinHitR } ?: false
                drawCircle(Color.Black.copy(alpha = 0.55f), 7.5f, pp + Offset(0f, 1f))
                drawCircle(if (hov) Color.Yellow else Color.White, 6.2f, pp)
                drawCircle(if (hov) Color.Yellow else Color(0xFF94A3B8), 2.3f, pp)
                if (def.inputCount <= 6) drawText(textMeasurer, def.inputPinLabelFull(j), pp + Offset(10f, -6f), TextStyle(Color.White.copy(alpha = 0.82f), 8.sp))
            }
            for (j in 0 until def.outputCount) {
                val pp = box.outputPos(j, def.outputCount)
                val isSrc = (wiringFrom?.instanceId == box.chip.instanceId && wiringFrom.pinIndex == j) || (wiringStartLocal?.end?.instanceId == box.chip.instanceId && wiringStartLocal?.end?.pinIndex == j)
                drawCircle(Color.Black.copy(alpha = 0.55f), 7.5f, pp + Offset(0f, 1f))
                drawCircle(if (isSrc) Color.Yellow else Color(0xFFA7F3D0), 6.8f, pp)
                drawCircle(if (isSrc) Color.Yellow else Color(0xFF22C55E), 2.8f, pp)
                if (isSrc) drawCircle(Color.Yellow.copy(alpha = 0.22f), 16f, pp)
                if (def.outputCount <= 6) {
                    val lbl = def.outputPinLabelFull(j)
                    drawText(textMeasurer, lbl, pp + Offset(-12f - lbl.length * 5.3f, -6f), TextStyle(Color.White.copy(alpha = 0.82f), 8.sp))
                }
            }
        }
    }
}

private fun wireStyleForWidth(busWidth: Int): Pair<Color, Float> = when (busWidth) {
    4 -> Color(0xFFF59E0B) to 4.2f
    8 -> Color(0xFF60A5FA) to 6.0f
    else -> Color(0xFF7ED8B6) to 2.7f
}

private fun DrawScope.drawTerminalPill(center: Offset, name: String, bg: Color, border: Color, isInput: Boolean, highlight: Boolean, textMeasurer: androidx.compose.ui.text.TextMeasurer) {
    val w = 70f + name.length * 3.6f; val h = 30f
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

private fun DrawScope.drawWire(from: Offset, to: Offset, color: Color, isSelected: Boolean, thickPx: Float = 2.7f, dash: Boolean = false) {
    val dx = to.x - from.x
    val ctrl = min(180f, max(50f, abs(dx) * 0.58f))
    val path = Path().apply { moveTo(from.x, from.y); cubicTo(from.x + ctrl, from.y, to.x - ctrl, to.y, to.x, to.y) }
    if (isSelected || color.alpha > 0.5f) drawPath(path, color.copy(alpha = 0.16f), style = Stroke(width = thickPx + 5.3f))
    if (dash) drawPath(path, color, style = Stroke(width = thickPx, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)))
    else drawPath(path, color, style = Stroke(width = thickPx))
    drawCircle(color, thickPx * 0.75f + 1.2f, to)
    drawCircle(Color.Black.copy(alpha = 0.7f), 1.3f, to)
}

private fun distPointToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ap = p - a; val ab = b - a; val ab2 = ab.x * ab.x + ab.y * ab.y
    if (ab2 == 0f) return (p - a).getDistance()
    var t = (ap.x * ab.x + ap.y * ab.y) / ab2; t = t.coerceIn(0f, 1f)
    val proj = Offset(a.x + ab.x * t, a.y + ab.y * t)
    return (p - proj).getDistance()
}
private fun distPointToBezier(p: Offset, from: Offset, to: Offset): Float {
    val dx = to.x - from.x; val ctrl = min(180f, max(50f, abs(dx) * 0.58f))
    var best = Float.MAX_VALUE; var prev = from; val steps = 24
    for (i in 1..steps) {
        val t = i / steps.toFloat(); val mt = 1 - t
        val x = mt * mt * mt * from.x + 3 * mt * mt * t * (from.x + ctrl) + 3 * mt * t * t * (to.x - ctrl) + t * t * t * to.x
        val y = mt * mt * mt * from.y + 3 * mt * mt * t * from.y + 3 * mt * t * t * to.y + t * t * t * to.y
        val cur = Offset(x, y); val d = distPointToSegment(p, prev, cur); if (d < best) best = d; prev = cur; if (best < 2f) return best
    }
    return best
}
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitFirstDown(requireUnconsumed: Boolean = true): androidx.compose.ui.input.pointer.PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
            val down = event.changes.firstOrNull { if (requireUnconsumed) !it.isConsumed else true } ?: continue; return down
        }
    }
}
private fun Offset.getDistance(): Float = hypot(x, y)

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
    CircuitCanvas(level, gates, wires, outputMaps, inputPositions, outputPositions, wiringFrom, onCreateWire, onStartWiring, onCancelWiring, onGateMove, onGateMove, onInputTermMove, onInputTermMove, onOutputTermMove, onOutputTermMove, onGateDelete, onWireDelete, onOutputMapDelete, dragGhostLineEnd, onGhostLine, modifier)
}
