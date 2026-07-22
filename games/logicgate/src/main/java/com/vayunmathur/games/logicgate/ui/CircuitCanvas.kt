package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.data.*
import kotlin.math.max

data class GateRect(val chip: PlacedChip, val left: Float, val top: Float, val width: Float, val height: Float) {
    fun inputPos(i: Int, count: Int): Offset {
        if (count == 0) return Offset(left, top + height / 2f)
        val gap = height / (count + 1)
        return Offset(left, top + gap * (i + 1))
    }
    fun outputPos(i: Int, count: Int): Offset {
        if (count == 0) return Offset(left + width, top + height / 2f)
        val gap = height / (count + 1)
        return Offset(left + width, top + gap * (i + 1))
    }
    fun contains(p: Offset): Boolean = p.x in left..left + width && p.y in top..top + height
}

@Composable
fun CircuitCanvas(
    level: LevelDef,
    gates: List<PlacedChip>,
    wires: List<Wire>,
    outputMaps: List<OutputMapping>,
    wiringFrom: WireEnd?,
    onStartWiringOutput: (WireEnd) -> Unit,
    onCompleteWiringInput: (WireEnd) -> Unit,
    onGateDrag: (String, Float, Float) -> Unit,
    onGateLongPress: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val chipDefs = gates.associate { it.instanceId to ChipLibrary.get(it.chipId) }

    fun computeRects(): List<GateRect> {
        return gates.map { g ->
            val def = ChipLibrary.get(g.chipId)
            val w = 120f
            val h = max(56f, 20f * max(def.inputCount, def.outputCount) + 24f)
            GateRect(g, g.x, g.y, w, h)
        }
    }

    var rects by remember(gates) { mutableStateOf(computeRects()) }
    // keep in sync
    rects = computeRects()
    val rectById = rects.associateBy { it.chip.instanceId }

    fun inputSourcePos(idx: Int): Offset {
        val spacing = 52f
        return Offset(22f, 70f + idx * spacing)
    }
    fun outputSinkPos(idx: Int): Offset {
        val spacing = 52f
        return Offset(760f, 70f + idx * spacing)
    }

    fun hitOutputPin(pos: Offset): WireEnd? {
        // check external inputs
        for ((i, _) in level.inputs.withIndex()) {
            if ((pos - inputSourcePos(i)).getDistance() < 18f) return WireEnd("__IN_$i", 0)
        }
        for (gr in rects) {
            val def = ChipLibrary.get(gr.chip.chipId)
            for (j in 0 until def.outputCount) {
                if ((pos - gr.outputPos(j, def.outputCount)).getDistance() < 16f) {
                    return WireEnd(gr.chip.instanceId, j)
                }
            }
        }
        return null
    }

    fun hitInputPin(pos: Offset): WireEnd? {
        for (gr in rects) {
            val def = ChipLibrary.get(gr.chip.chipId)
            for (j in 0 until def.inputCount) {
                if ((pos - gr.inputPos(j, def.inputCount)).getDistance() < 16f) {
                    return WireEnd(gr.chip.instanceId, j)
                }
            }
        }
        for ((i, _) in level.outputs.withIndex()) {
            if ((pos - outputSinkPos(i)).getDistance() < 18f) return WireEnd("__OUT_$i", 0)
        }
        return null
    }

    fun hitGate(pos: Offset): PlacedChip? {
        for (gr in rects.reversed()) if (gr.contains(pos)) return gr.chip
        return null
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101820))
            .pointerInput(rects, wiringFrom) {
                detectTapGestures(
                    onTap = { tapPos ->
                        if (wiringFrom != null) {
                            val dest = hitInputPin(tapPos)
                            if (dest != null) {
                                onCompleteWiringInput(dest)
                            }
                        } else {
                            val src = hitOutputPin(tapPos)
                            if (src != null) onStartWiringOutput(src)
                        }
                    },
                    onLongPress = { lp ->
                        hitGate(lp)?.let { onGateLongPress(it.instanceId) }
                    }
                )
            }
            .pointerInput(rects) {
                detectDragGestures(
                    onDragStart = { /* handled below via move */ },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val gate = hitGate(change.position - dragAmount) ?: hitGate(change.position)
                        if (gate != null) {
                            val cur = rectById[gate.instanceId] ?: return@detectDragGestures
                            val nx = (cur.left + dragAmount.x).coerceIn(0f, 820f - cur.width)
                            val ny = (cur.top + dragAmount.y).coerceIn(0f, 1600f - cur.height)
                            onGateDrag(gate.instanceId, nx, ny)
                        }
                    }
                )
            }
    ) {
        // grid
        val step = 22f
        var x = 0f
        while (x < size.width) {
            var y = 0f
            while (y < size.height) {
                drawCircle(Color.White.copy(alpha = 0.05f), 0.9f, Offset(x, y))
                y += step
            }
            x += step
        }

        // helper resolvers
        fun resolveSource(end: WireEnd): Offset? {
            if (end.instanceId.startsWith("__IN_")) {
                val idx = end.instanceId.removePrefix("__IN_").toIntOrNull() ?: return null
                return inputSourcePos(idx)
            }
            val gr = rectById[end.instanceId] ?: return null
            val def = chipDefs[end.instanceId] ?: return null
            return gr.outputPos(end.pinIndex.coerceIn(0, max(0, def.outputCount - 1)), def.outputCount)
        }
        fun resolveSink(end: WireEnd): Offset? {
            if (end.instanceId.startsWith("__OUT_")) {
                val idx = end.instanceId.removePrefix("__OUT_").toIntOrNull() ?: return null
                return outputSinkPos(idx)
            }
            val gr = rectById[end.instanceId] ?: return null
            val def = chipDefs[end.instanceId] ?: return null
            return gr.inputPos(end.pinIndex.coerceIn(0, max(0, def.inputCount - 1)), def.inputCount)
        }

        // wires
        for (w in wires) {
            val from = resolveSource(w.from) ?: continue
            val to = resolveSink(w.to) ?: continue
            drawWire(from, to, Color(0xFF7ED8B6))
        }
        for (om in outputMaps) {
            val from = resolveSource(om.from) ?: continue
            val to = outputSinkPos(om.outputIndex)
            drawWire(from, to, Color(0xFFFDE68A))
        }

        // input nodes
        for ((i, name) in level.inputs.withIndex()) {
            val p = inputSourcePos(i)
            val isActive = wiringFrom?.instanceId == "__IN_$i"
            drawCircle(if (isActive) Color.Yellow else Color(0xFF38BDF8), 11f, p)
            drawCircle(Color.Black, 4f, p)
            drawText(textMeasurer, text = name, topLeft = p + Offset(16f, -8f), style = TextStyle(Color.White, 12.sp))
        }
        for ((i, name) in level.outputs.withIndex()) {
            val p = outputSinkPos(i)
            drawCircle(Color(0xFFF87171), 11f, p)
            drawCircle(Color.Black, 4f, p)
            drawText(textMeasurer, text = name, topLeft = p + Offset(-48f - name.length * 2, -8f), style = TextStyle(Color.White, 12.sp))
        }

        // gates
        for (gr in rects) {
            val def = ChipLibrary.get(gr.chip.chipId)
            val boxColor = when (def.category) {
                ChipCategory.PRIMITIVE -> Color(0xFF1E3A4A)
                ChipCategory.FOUNDATION -> Color(0xFF2A9D8F)
                ChipCategory.ROUTING -> Color(0xFFE9C46A)
                ChipCategory.ARITH -> Color(0xFFF4A261)
                ChipCategory.MEMORY -> Color(0xFF9A7DFF)
                ChipCategory.CPU -> Color(0xFFFF4D6D)
            }
            drawRect(boxColor, Offset(gr.left, gr.top), GeomSize(gr.width, gr.height))
            drawRect(Color.White.copy(alpha = 0.25f), Offset(gr.left, gr.top), GeomSize(gr.width, gr.height), style = Stroke(1f))
            drawText(textMeasurer, text = def.displayName, topLeft = Offset(gr.left + 6f, gr.top + 4f), style = TextStyle(if (def.category == ChipCategory.ROUTING) Color.Black else Color.White, 11.sp))
            drawText(textMeasurer, text = "${def.nandCost}N", topLeft = Offset(gr.left + gr.width - 28f, gr.top + 4f), style = TextStyle(Color.White.copy(alpha = 0.7f), 9.sp))

            for (j in 0 until def.inputCount) {
                val pp = gr.inputPos(j, def.inputCount)
                drawCircle(Color.White, 5.5f, pp)
                val lbl = def.inputs[j]
                drawText(textMeasurer, text = lbl, topLeft = pp + Offset(8f, -6f), style = TextStyle(Color.White.copy(alpha = 0.8f), 8.sp))
            }
            for (j in 0 until def.outputCount) {
                val pp = gr.outputPos(j, def.outputCount)
                val isSrc = wiringFrom?.instanceId == gr.chip.instanceId && wiringFrom.pinIndex == j
                drawCircle(if (isSrc) Color.Yellow else Color(0xFFA7F3D0), 5.5f, pp)
                val lbl = def.outputs[j]
                drawText(textMeasurer, text = lbl, topLeft = pp + Offset(-22f, -6f), style = TextStyle(Color.White.copy(alpha = 0.8f), 8.sp))
            }
        }
    }
}

private fun DrawScope.drawWire(from: Offset, to: Offset, color: Color) {
    val midX = (from.x + to.x) * 0.5f
    drawLine(color, from, Offset(midX, from.y), strokeWidth = 2.8f)
    drawLine(color, Offset(midX, from.y), Offset(midX, to.y), strokeWidth = 2.8f)
    drawLine(color, Offset(midX, to.y), to, strokeWidth = 2.8f)
    // arrow endpoint
    drawCircle(color, 3f, to)
}
