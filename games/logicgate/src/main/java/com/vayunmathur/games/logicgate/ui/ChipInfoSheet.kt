package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.model.ChipInfo
import com.vayunmathur.games.logicgate.domain.model.ChipSpec
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text

/**
 * What a tray chip is and how it behaves, opened by tapping its card. [onAdd] places it on the
 * board — the same thing tapping the card used to do directly.
 */
@Composable
fun ChipInfoSheet(chip: ChipSpec, onAdd: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(chipLabel(chip.id), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.lg_cost, chip.nandCost),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipInfo.blurb(chip.id)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge) }
            PinLine(stringResource(R.string.lg_brief_inputs), chip.inputs)
            PinLine(stringResource(R.string.lg_brief_outputs), chip.outputs)
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lg_add_to_board)) }
        }
    }
}
