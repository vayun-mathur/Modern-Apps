package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.Wire

/** What a touch landed on. */
sealed interface Hit {
    /** A pin, which starts a wire. */
    data class OnPin(val pin: PinRef) : Hit

    /** A node body, which moves it. */
    data class OnNode(val id: NodeId) : Hit

    /** A wire, which selects it. */
    data class OnWire(val wire: Wire) : Hit

    /** Nothing, which pans. */
    data object Empty : Hit
}

/**
 * Pin, then body, then wire. Pins sit on node edges, so a pin wins within [pinRadius] when the
 * touch is outside the body, but only within [innerPinRadius] once inside it — otherwise a small
 * gate would be all pins and impossible to pick up.
 */
object HitTest {

    fun hit(layout: CircuitLayout, world: Offset, pinRadius: Float, innerPinRadius: Float, wireRadius: Float): Hit {
        val body = layout.circuit.nodes.lastOrNull { layout.rects[it.id]?.contains(world) == true }
        val pin = nearestPin(layout, world, if (body == null) pinRadius else innerPinRadius)
        if (pin != null) return Hit.OnPin(pin)
        if (body != null) return Hit.OnNode(body.id)
        return nearestWire(layout, world, wireRadius)?.let { Hit.OnWire(it) } ?: Hit.Empty
    }

    fun nearestPin(layout: CircuitLayout, world: Offset, radius: Float, accept: (PinRef) -> Boolean = { true }): PinRef? =
        layout.anchors.entries
            .filter { accept(it.key) }
            .map { it.key to (it.value - world).getDistance() }
            .filter { it.second <= radius }
            .minByOrNull { it.second }
            ?.first

    private fun nearestWire(layout: CircuitLayout, world: Offset, radius: Float): Wire? =
        layout.circuit.wires.mapNotNull { w ->
            val a = layout.anchors[w.from] ?: return@mapNotNull null
            val b = layout.anchors[w.to] ?: return@mapNotNull null
            w to GateGeometry.distanceToPolyline(world, GateGeometry.samples(a, b))
        }.filter { it.second <= radius }.minByOrNull { it.second }?.first
}
