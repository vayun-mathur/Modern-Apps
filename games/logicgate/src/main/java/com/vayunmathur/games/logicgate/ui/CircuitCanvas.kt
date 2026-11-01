package com.vayunmathur.games.logicgate.ui

import android.content.res.Resources
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.toSize
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.ConnectCheck
import com.vayunmathur.games.logicgate.domain.model.LevelOutput
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.platform.GameUiState
import com.vayunmathur.games.logicgate.platform.LogicActions
import com.vayunmathur.games.logicgate.platform.Selection
import com.vayunmathur.games.logicgate.ui.canvas.CameraState
import com.vayunmathur.games.logicgate.ui.canvas.CanvasController
import com.vayunmathur.games.logicgate.ui.canvas.CircuitColors
import com.vayunmathur.games.logicgate.ui.canvas.CircuitLayout
import com.vayunmathur.games.logicgate.ui.canvas.Drag
import com.vayunmathur.games.logicgate.ui.canvas.NodeStyle
import com.vayunmathur.games.logicgate.ui.canvas.PaintContext
import com.vayunmathur.games.logicgate.ui.canvas.TrashZone
import com.vayunmathur.games.logicgate.ui.canvas.circuitGestures
import com.vayunmathur.games.logicgate.ui.canvas.drawGhostWire
import com.vayunmathur.games.logicgate.ui.canvas.drawGrid
import com.vayunmathur.games.logicgate.ui.canvas.drawNode
import com.vayunmathur.games.logicgate.ui.canvas.drawPins
import com.vayunmathur.games.logicgate.ui.canvas.drawWire
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.rememberHaptics
import com.vayunmathur.library.ui.rememberMessenger
import kotlinx.coroutines.launch

/**
 * The circuit board: one canvas drawn in world coordinates under [cameraState], with every
 * gesture going through a [CanvasController]. Live wire values come from [GameUiState.live];
 * gates dragged onto [trash] are deleted.
 */
@Composable
fun CircuitCanvas(
    state: GameUiState,
    cameraState: CameraState,
    actions: LogicActions,
    trash: TrashZone,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) { CircuitColors.from(scheme) }
    val measurer = rememberTextMeasurer()
    val messenger = rememberMessenger()
    val haptics = rememberHaptics()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val lookup = remember(state.level) { Chips.lookup(state.level.io) }
    val controller = remember(cameraState, trash) { CanvasController(cameraState, density, trash) }
    val shake = remember { Animatable(0f) }
    var shakePin by remember { mutableStateOf<PinRef?>(null) }
    var editingInput by remember { mutableStateOf<Int?>(null) }
    val shown = controller.preview(state.circuit)
    val layout = remember(shown, lookup) { CircuitLayout(shown, lookup) }

    SideEffect {
        controller.bind(state, actions, layout, lookup)
        controller.onPickup = { haptics.longPress() }
        controller.onTrashEnter = { haptics.tick() }
        controller.onEditBus = { editingInput = it }
        controller.onReject = { pin, check ->
            haptics.reject()
            messenger.show(rejectMessage(resources, check))
            shakePin = pin
            scope.launch {
                for (x in SHAKE) shake.animateTo(x, Motion.over(SHAKE_STEP_MS))
                shakePin = null
            }
        }
    }

    fun fitIfRequested() {
        if (cameraState.viewport.width > 0f && cameraState.fittedToken != state.fitToken) {
            cameraState.fit(layout.bounds)
            cameraState.fittedToken = state.fitToken
        }
    }
    LaunchedEffect(state.fitToken) { fitIfRequested() }

    val selectedNode = (state.selection as? Selection.OfNode)?.id
    val selectedWire = (state.selection as? Selection.OfWire)?.wire
    val expected = state.probeCase?.let { state.verify?.cases?.getOrNull(it)?.expected }
    val oscillating = state.live.oscillating + state.verify?.oscillating.orEmpty()

    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                cameraState.viewport = it.toSize()
                fitIfRequested()
            }
            .pointerInput(controller) { circuitGestures(controller) },
    ) {
        drawRect(colors.background)
        val cam = cameraState.camera
        drawGrid(cam, colors.grid)
        val ctx = PaintContext(colors, measurer, layout, state.live.values)
        withTransform({
            translate(cam.offset.x, cam.offset.y)
            scale(cam.scale, cam.scale, Offset.Zero)
        }) {
            for (w in layout.circuit.wires) {
                val a = layout.anchors[w.from] ?: continue
                val b = layout.anchors[w.to] ?: continue
                drawWire(ctx, a to b, layout.pin(w.from)?.width ?: 1, state.live.values[w.from], w == selectedWire)
            }
            val drag = controller.drag
            if (drag is Drag.Wiring) {
                layout.anchors[drag.from]?.let { a ->
                    val ends = if (drag.from.side == PinSide.OUT) a to drag.world else drag.world to a
                    drawGhostWire(ctx, ends, layout.pin(drag.from)?.width ?: 1)
                }
            }
            val doomed = (drag as? Drag.Moving)?.id?.takeIf { trash.hovering }
            for (n in layout.circuit.nodes) {
                val want = (n as? LevelOutput)?.let { expected?.getOrNull(it.index) }
                drawNode(ctx, n, NodeStyle(n.id == selectedNode, n.id in oscillating || n.id == doomed, want))
            }
            val highlight = (drag as? Drag.Wiring)?.compatible.orEmpty()
            val shaking = shakePin?.let { it to shake.value }
            for (n in layout.circuit.nodes) drawPins(ctx, n, highlight, shaking)
        }
    }

    editingInput?.let { i ->
        BusValueDialog(
            pin = state.level.inputs[i],
            value = state.manualInputs[i],
            onValue = { actions.setInput(i, it) },
            onDismiss = { editingInput = null },
        )
    }
}

private fun rejectMessage(res: Resources, check: ConnectCheck): String = when (check) {
    is ConnectCheck.WidthMismatch ->
        res.getString(R.string.lg_wire_width, check.fromWidth, check.toWidth, adapterFor(check.fromWidth, check.toWidth))
    else -> res.getString(R.string.lg_wire_same_side)
}

/** The chip that converts between two bus widths. */
private fun adapterFor(from: Int, to: Int): String {
    val id = when {
        from == 8 && to == 1 -> Chips.SPLIT8
        from == 1 && to == 8 -> Chips.JOIN8
        from == 4 && to == 1 -> Chips.SPLIT4
        from == 1 && to == 4 -> Chips.JOIN4
        from == 8 -> Chips.NIB_SPLIT
        else -> Chips.NIB_JOIN
    }
    return Chips[id]?.label ?: id
}

private val SHAKE = listOf(7f, -7f, 5f, -5f, 2f, 0f)
private const val SHAKE_STEP_MS = 45
