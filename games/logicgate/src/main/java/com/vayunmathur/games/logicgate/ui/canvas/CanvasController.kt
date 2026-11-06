package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.ConnectCheck
import com.vayunmathur.games.logicgate.domain.model.Gate
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.PinLookup
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.Point
import com.vayunmathur.games.logicgate.platform.GameUiState
import com.vayunmathur.games.logicgate.platform.LogicActions
import com.vayunmathur.games.logicgate.platform.Selection

/** A gesture in flight. */
sealed interface Drag {
    /** A wire being drawn from [from] to the finger at [world]; [compatible] pins may receive it. */
    data class Wiring(val from: PinRef, val world: Offset, val compatible: Set<PinRef>) : Drag

    /**
     * A node following the finger. After release it stays until the committed circuit arrives
     * ([releasedOn] is the circuit at release), so the node never flickers back for a frame.
     */
    data class Moving(val id: NodeId, val origin: Point, val delta: Offset, val releasedOn: Circuit? = null) : Drag {
        val target: Point
            get() = Point(GateGeometry.snap(origin.x + delta.x), GateGeometry.snap(origin.y + delta.y))
    }
}

/**
 * Turns canvas gestures into editor actions. Touch radii are in dp and scale with zoom. A gate
 * carried onto [trash] is deleted when dropped there.
 */
@Stable
class CanvasController(
    private val camera: CameraState,
    private val density: Float,
    private val trash: TrashZone,
) : CanvasGestureHandler {
    var drag by mutableStateOf<Drag?>(null)
        private set

    private var state: GameUiState? = null
    private var actions: LogicActions = LogicActions.Noop
    private var layout: CircuitLayout? = null
    private var lookup: PinLookup? = null

    var onReject: (PinRef, ConnectCheck) -> Unit = { _, _ -> }
    var onEditBus: (Int) -> Unit = {}
    var onPickup: () -> Unit = {}
    var onTrashEnter: () -> Unit = {}

    fun bind(state: GameUiState, actions: LogicActions, layout: CircuitLayout, lookup: PinLookup) {
        this.state = state
        this.actions = actions
        this.layout = layout
        this.lookup = lookup
        val m = drag as? Drag.Moving
        if (m?.releasedOn != null && m.releasedOn != state.circuit) drag = null
    }

    fun preview(circuit: Circuit): Circuit =
        (drag as? Drag.Moving)?.takeIf { it.releasedOn == null || it.releasedOn == circuit }
            ?.let { circuit.moveNode(it.id, it.target) } ?: circuit

    private fun world(screen: Offset) = camera.camera.toWorld(screen)

    private fun worldDp(dp: Float) = dp * density / camera.camera.scale

    override fun hit(screen: Offset): Hit {
        val l = layout ?: return Hit.Empty
        return HitTest.hit(l, world(screen), worldDp(PIN_TOUCH_DP), worldDp(INNER_PIN_TOUCH_DP), worldDp(WIRE_TOUCH_DP))
    }

    override fun tap(hit: Hit) {
        when (hit) {
            is Hit.OnPin -> tapNode(hit.pin.node)
            is Hit.OnNode -> tapNode(hit.id)
            is Hit.OnWire -> actions.select(Selection.OfWire(hit.wire))
            Hit.Empty -> actions.select(null)
        }
    }

    private fun tapNode(id: NodeId) {
        val s = state ?: return
        when (val node = s.circuit.node(id)) {
            is LevelInput -> {
                val width = s.io.inputs.getOrNull(node.index)?.width ?: return
                val current = s.manualInputs.getOrElse(node.index) { 0 }
                if (width == 1) actions.setInput(node.index, 1 - current) else onEditBus(node.index)
            }
            is Gate -> actions.select(Selection.OfNode(id))
            else -> actions.select(null)
        }
    }

    override fun startWire(pin: PinRef, screen: Offset) {
        val s = state ?: return
        val lk = lookup ?: return
        val l = layout ?: return
        val compatible = l.anchors.keys.filter { it != pin && s.circuit.check(pin, it, lk) is ConnectCheck.Ok }.toSet()
        drag = Drag.Wiring(pin, world(screen), compatible)
        onPickup()
    }

    override fun dragWire(screen: Offset) {
        (drag as? Drag.Wiring)?.let { drag = it.copy(world = world(screen)) }
    }

    override fun dropWire(screen: Offset) {
        val w = drag as? Drag.Wiring ?: return
        drag = null
        val s = state ?: return
        val lk = lookup ?: return
        val l = layout ?: return
        val at = world(screen)
        val radius = worldDp(PIN_TOUCH_DP)
        val target = HitTest.nearestPin(l, at, radius) { it in w.compatible }
            ?: HitTest.nearestPin(l, at, radius) { it != w.from }
            ?: return
        when (val check = s.circuit.check(w.from, target, lk)) {
            is ConnectCheck.Ok -> actions.connect(w.from, target)
            else -> onReject(target, check)
        }
    }

    override fun startMove(id: NodeId) {
        val node = state?.circuit?.node(id) ?: return
        drag = Drag.Moving(id, node.pos, Offset.Zero)
        trash.armed = node is Gate || state?.sandbox != null
        onPickup()
    }

    override fun dragMove(delta: Offset, screen: Offset) {
        val m = drag as? Drag.Moving ?: return
        drag = m.copy(delta = m.delta + delta / camera.camera.scale)
        val over = trash.armed && trash.contains(screen)
        if (over && !trash.hovering) onTrashEnter()
        trash.hovering = over
    }

    override fun endMove(screen: Offset) {
        val m = drag as? Drag.Moving ?: return
        val s = state ?: return
        val delete = trash.armed && trash.contains(screen)
        disarm()
        if (delete) {
            drag = null
            actions.deleteNode(m.id)
            return
        }
        if (m.target == m.origin) {
            drag = null
            return
        }
        drag = m.copy(releasedOn = s.circuit)
        actions.moveNode(m.id, m.target)
    }

    override fun pan(delta: Offset) = camera.pan(delta)

    override fun zoom(centroid: Offset, factor: Float) = camera.zoom(centroid, factor)

    override fun cancelDrag() {
        drag = null
        disarm()
    }

    private fun disarm() {
        trash.armed = false
        trash.hovering = false
    }

    private companion object {
        const val PIN_TOUCH_DP = 24f
        const val INNER_PIN_TOUCH_DP = 14f
        const val WIRE_TOUCH_DP = 12f
    }
}
