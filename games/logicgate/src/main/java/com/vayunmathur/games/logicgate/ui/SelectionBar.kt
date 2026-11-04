package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.model.Gate
import com.vayunmathur.games.logicgate.platform.GameUiState
import com.vayunmathur.games.logicgate.platform.LogicActions
import com.vayunmathur.games.logicgate.platform.Selection
import com.vayunmathur.library.ui.BannerVisibility
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/** Contextual actions for the selected gate or wire; deleting is always an explicit tap here. */
@Composable
fun SelectionBar(state: GameUiState, actions: LogicActions, modifier: Modifier = Modifier) {
    val wireLabel = stringResource(R.string.lg_selected_wire)
    val label = when (val sel = state.selection) {
        is Selection.OfNode -> (state.circuit.node(sel.id) as? Gate)?.let { chipLabel(it.chipId) }
        is Selection.OfWire -> wireLabel
        null -> null
    }
    var lastLabel by remember { mutableStateOf("") }
    if (label != null) lastLabel = label
    BannerVisibility(visible = label != null, modifier = modifier) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 4.dp,
        ) {
            Row(
                Modifier.padding(start = Spacing.lg, end = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(lastLabel, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = actions::deleteSelection) {
                    IconDelete(tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.lg_delete), color = MaterialTheme.colorScheme.error)
                }
                IconButton(onClick = { actions.select(null) }) { IconClose() }
            }
        }
    }
}
