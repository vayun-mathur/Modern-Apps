package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
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

    /** Picks up node [id], grabbed at [screen] so it keeps its offset to the finger. */
    fun startMove(id: NodeId, screen: Offset)

    /** The finger carrying a node is at [screen] (also called after the camera moved under it). */
    fun dragMove(screen: Offset)
    fun endMove(screen: Offset)
    fun pan(delta: Offset)
    fun zoom(centroid: Offset, factor: Float)
    fun cancelDrag()

    /**
     * Called while a wire or node is carried with the finger at [screen], [dtMs] after the last
     * call: pans the board when the finger is near an edge and returns true while it keeps doing so.
     */
    fun autoPan(screen: Offset, dtMs: Float): Boolean
}

private enum class Mode { PENDING, WIRE, MOVE, PAN, PINCH }

/**
 * The whole editor in one gesture loop. What the finger first touched decides what a drag does —
 * a pin draws a wire, a body moves it, anything else pans. A second finger pinches and pans the
 * board; while a wire or node is being carried it stays on the first finger, so a two-finger pan
 * (or holding the finger near an edge, which auto-pans) reaches parts of the board off screen.
 * Nothing happens on finger-down, so a stray touch never edits the circuit.
 */
suspend fun PointerInputScope.circuitGestures(h: CanvasGestureHandler) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val hit = h.hit(down.position)
        var mode = Mode.PENDING
        var last = down.position
        var autoPanning = false
        var lastTime = down.uptimeMillis
        while (true) {
            // While auto-panning, wait at most one frame for input so the board keeps moving
            // under a finger held still near the edge.
            val event: PointerEvent? = if (autoPanning) {
                withTimeoutOrNull(AUTO_PAN_FRAME_MS) { awaitPointerEvent(PointerEventPass.Main) }
            } else {
                awaitPointerEvent(PointerEventPass.Main)
            }
            if (event == null) {
                // The carrying finger is held still near an edge: keep the board moving.
                lastTime += AUTO_PAN_FRAME_MS
                autoPanning = h.autoPan(last, AUTO_PAN_FRAME_MS.toFloat())
                continue
            }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            val carrying = mode == Mode.WIRE || mode == Mode.MOVE
            val primary = event.changes.firstOrNull { it.id == down.id && it.pressed }
            if (carrying && primary == null) {
                // The carrying finger lifted while another is still down: drop it where it was.
                finish(h, mode, hit, last)
                mode = Mode.PINCH
            } else if (pressed.size > 1 || mode == Mode.PINCH) {
                if (pressed.size > 1) {
                    h.zoom(event.calculateCentroid(), event.calculateZoom())
                    h.pan(event.calculatePan())
                }
                if (carrying && primary != null) {
                    last = primary.position
                    follow(h, mode, last)
                } else {
                    mode = Mode.PINCH
                }
                autoPanning = false
            } else {
                val pos = pressed.first().position
                if (mode == Mode.PENDING && (pos - down.position).getDistance() > viewConfiguration.touchSlop) {
                    mode = when (hit) {
                        is Hit.OnPin -> Mode.WIRE.also { h.startWire(hit.pin, pos) }
                        is Hit.OnNode -> Mode.MOVE.also { h.startMove(hit.id, down.position) }
                        else -> Mode.PAN
                    }
                }
                when (mode) {
                    Mode.WIRE, Mode.MOVE -> follow(h, mode, pos)
                    Mode.PAN -> h.pan(pos - last)
                    else -> Unit
                }
                if (mode != Mode.PENDING) last = pos
                if (mode == Mode.WIRE || mode == Mode.MOVE) {
                    val now = pressed.first().uptimeMillis
                    autoPanning = h.autoPan(pos, (now - lastTime).coerceIn(0L, MAX_AUTO_PAN_STEP_MS).toFloat())
                    lastTime = now
                }
            }
            event.changes.forEach { it.consume() }
        }
        finish(h, mode, hit, last)
    }
}

private fun follow(h: CanvasGestureHandler, mode: Mode, at: Offset) {
    if (mode == Mode.WIRE) h.dragWire(at) else h.dragMove(at)
}

private fun finish(h: CanvasGestureHandler, mode: Mode, hit: Hit, at: Offset) {
    when (mode) {
        Mode.PENDING -> h.tap(hit)
        Mode.WIRE -> h.dropWire(at)
        Mode.MOVE -> h.endMove(at)
        else -> Unit
    }
}

private const val AUTO_PAN_FRAME_MS = 16L
private const val MAX_AUTO_PAN_STEP_MS = 48L
