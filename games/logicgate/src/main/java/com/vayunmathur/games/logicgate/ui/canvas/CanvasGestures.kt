package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.PinRef

/** Callbacks for [circuitGestures]; positions are in canvas pixels. */
interface CanvasGestureHandler {
    fun hit(screen: Offset): Hit
    fun tap(hit: Hit)
    fun startWire(pin: PinRef, screen: Offset)
    fun dragWire(screen: Offset)
    fun dropWire(screen: Offset)
    fun startMove(id: NodeId)
    fun dragMove(delta: Offset, screen: Offset)
    fun endMove(screen: Offset)
    fun pan(delta: Offset)
    fun zoom(centroid: Offset, factor: Float)
    fun cancelDrag()
}

private enum class Mode { PENDING, WIRE, MOVE, PAN, PINCH }

/**
 * The whole editor in one gesture loop. What the finger first touched decides what a drag does —
 * a pin draws a wire, a body moves it, anything else pans — and a second finger always turns the
 * gesture into a pinch. Nothing happens on finger-down, so a stray touch never edits the circuit.
 */
suspend fun PointerInputScope.circuitGestures(h: CanvasGestureHandler) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val hit = h.hit(down.position)
        var mode = Mode.PENDING
        var last = down.position
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size > 1 || mode == Mode.PINCH) {
                if (mode == Mode.WIRE || mode == Mode.MOVE) h.cancelDrag()
                if (pressed.size > 1) {
                    h.zoom(event.calculateCentroid(), event.calculateZoom())
                    h.pan(event.calculatePan())
                }
                mode = Mode.PINCH
                event.changes.forEach { it.consume() }
                continue
            }
            val change = pressed.first()
            val pos = change.position
            if (mode == Mode.PENDING && (pos - down.position).getDistance() > viewConfiguration.touchSlop) {
                mode = when (hit) {
                    is Hit.OnPin -> Mode.WIRE.also { h.startWire(hit.pin, pos) }
                    is Hit.OnNode -> Mode.MOVE.also { h.startMove(hit.id) }
                    else -> Mode.PAN
                }
            }
            when (mode) {
                Mode.WIRE -> h.dragWire(pos)
                Mode.MOVE -> h.dragMove(pos - last, pos)
                Mode.PAN -> h.pan(pos - last)
                else -> Unit
            }
            if (mode != Mode.PENDING) last = pos
            change.consume()
        }
        when (mode) {
            Mode.PENDING -> h.tap(hit)
            Mode.WIRE -> h.dropWire(last)
            Mode.MOVE -> h.endMove(last)
            else -> Unit
        }
    }
}
