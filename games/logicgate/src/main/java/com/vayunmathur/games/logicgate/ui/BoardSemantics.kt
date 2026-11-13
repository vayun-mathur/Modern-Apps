package com.vayunmathur.games.logicgate.ui

import android.content.res.Resources
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.model.Gate
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.LevelOutput
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.formatValue
import com.vayunmathur.games.logicgate.platform.GameUiState

/**
 * What TalkBack hears for the board, which is one drawn canvas: a summary (gate and wire count,
 * every input and output with its current value) plus one custom action per input, flipping a
 * 1-bit input ([onToggle] with its new value) or opening the value dialog for a bus ([onEditBus]),
 * exactly like tapping it.
 */
fun Modifier.boardSemantics(
    state: GameUiState,
    res: Resources,
    onToggle: (index: Int, value: Int) -> Unit,
    onEditBus: (index: Int) -> Unit,
): Modifier {
    val c = state.circuit
    val live = state.live.values
    val inputs = c.nodes.filterIsInstance<LevelInput>().sortedBy { it.index }.mapNotNull { n ->
        val pin = state.io.inputs.getOrNull(n.index) ?: return@mapNotNull null
        val manual = state.manualInputs.getOrElse(n.index) { 0 }
        Triple(n.index, pin, live[PinRef(n.id, PinSide.OUT, 0)] ?: manual)
    }
    val outputs = c.nodes.filterIsInstance<LevelOutput>().sortedBy { it.index }.mapNotNull { n ->
        val pin = state.io.outputs.getOrNull(n.index) ?: return@mapNotNull null
        pin to (c.wireInto(PinRef(n.id, PinSide.IN, 0))?.let { live[it.from] } ?: 0)
    }
    fun list(pins: List<Pair<String, String>>) =
        pins.joinToString(", ") { (name, value) -> res.getString(R.string.lg_cd_pin_value, name, value) }
    val description = buildString {
        val gates = c.nodes.count { it is Gate }
        append(
            res.getString(
                R.string.lg_cd_board,
                res.getQuantityString(R.plurals.lg_cd_board_gates, gates, gates),
                res.getQuantityString(R.plurals.lg_cd_board_wires, c.wires.size, c.wires.size),
            ),
        )
        if (inputs.isNotEmpty()) {
            append(' ').append(res.getString(R.string.lg_cd_board_inputs, list(inputs.map { (_, p, v) -> p.name to formatValue(v, p.width) })))
        }
        if (outputs.isNotEmpty()) {
            append(' ').append(res.getString(R.string.lg_cd_board_outputs, list(outputs.map { (p, v) -> p.name to formatValue(v, p.width) })))
        }
    }
    val actions = inputs.map { (index, pin, value) ->
        val shown = formatValue(value, pin.width)
        if (pin.width == 1) {
            val manual = state.manualInputs.getOrElse(index) { 0 }
            CustomAccessibilityAction(res.getString(R.string.lg_action_toggle_input, pin.name, shown)) {
                onToggle(index, 1 - manual)
                true
            }
        } else {
            CustomAccessibilityAction(res.getString(R.string.lg_action_set_input, pin.name, shown)) {
                onEditBus(index)
                true
            }
        }
    }
    return semantics {
        contentDescription = description
        customActions = actions
    }
}
