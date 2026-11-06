package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.CustomChip
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.LevelOutput
import com.vayunmathur.games.logicgate.domain.model.Node
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.platform.GameUiState
import com.vayunmathur.games.logicgate.platform.LogicActions
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * A sandbox component's details: its name and group (with the player's other groups one tap
 * away), its pins in chip order with rename and delete, and deleting the whole component. Name,
 * group and pin names are applied when the sheet closes.
 */
@Composable
fun ComponentSheet(state: GameUiState, actions: LogicActions, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val mode = state.sandbox ?: return
    var name by rememberSaveable(mode.id) { mutableStateOf(mode.name) }
    var group by rememberSaveable(mode.id) { mutableStateOf(mode.group) }
    val pinNames = remember(mode.id) { mutableStateMapOf<NodeId, String>() }
    var confirmDelete by remember { mutableStateOf(false) }
    val groups = remember(state.catalog) { state.catalog.customs.map { it.group }.distinct().sorted() }
    val chip = CustomChip(mode.id, mode.name, mode.group, state.board)
    val pins: List<Node> = chip.inputNodes + chip.outputNodes

    fun close() {
        actions.renameComponent(name, group)
        for ((id, pinName) in pinNames) actions.renamePin(id, pinName)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = ::close) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.lg_component_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = group,
                onValueChange = { group = it },
                label = { Text(stringResource(R.string.lg_component_group)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (groups.size > 1 || groups.singleOrNull() != group) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(groups) { g -> AssistChip(onClick = { group = g }, label = { Text(g) }) }
                }
            }
            Text(stringResource(R.string.lg_component_pins), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.lg_component_pins_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pins.isEmpty()) Text(stringResource(R.string.lg_component_no_pins), style = MaterialTheme.typography.bodyMedium)
            for (node in pins) {
                val pin = pinOf(state.board, node) ?: continue
                PinRow(
                    node = node,
                    pin = pin,
                    value = pinNames[node.id] ?: pin.name,
                    onValue = { pinNames[node.id] = it.take(Board.MAX_PIN_NAME) },
                    onDelete = {
                        pinNames.remove(node.id)
                        actions.deleteNode(node.id)
                    },
                )
            }
            Button(onClick = ::close, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lg_done)) }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lg_component_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.lg_component_delete_title, mode.name),
            message = stringResource(R.string.lg_component_delete_message),
            confirmLabel = stringResource(R.string.lg_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDismiss()
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun PinRow(node: Node, pin: Pin, value: String, onValue: (String) -> Unit, onDelete: () -> Unit) {
    val kind = stringResource(if (node is LevelInput) R.string.lg_chip_input_label else R.string.lg_chip_output_label)
    val badge = if (pin.width > 1) " · " + stringResource(R.string.lg_bus_badge, pin.width) else ""
    val deleteDescription = stringResource(R.string.lg_cd_delete_pin, value)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            label = { Text(kind + badge) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = deleteDescription }) {
            IconDelete(tint = MaterialTheme.colorScheme.error)
        }
    }
}

private fun pinOf(board: Board, node: Node): Pin? = when (node) {
    is LevelInput -> board.io.inputs.getOrNull(node.index)
    is LevelOutput -> board.io.outputs.getOrNull(node.index)
    else -> null
}
