package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.levels.LevelDef
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconLightbulb
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/** The level's goal, pins and reward, with the hint held back until asked for. */
@Composable
fun BriefSheet(level: LevelDef, number: Int, onDismiss: () -> Unit) {
    var hintShown by rememberSaveable(level.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.lg_level_title, number, level.title), style = MaterialTheme.typography.headlineSmall)
            Text(level.brief, style = MaterialTheme.typography.bodyLarge)
            PinLine(stringResource(R.string.lg_brief_inputs), level.inputs)
            PinLine(stringResource(R.string.lg_brief_outputs), level.outputs)
            if (level.unlocks.isNotEmpty()) {
                val chips = level.unlocks.joinToString { Chips[it]?.label ?: it }
                Text(stringResource(R.string.lg_brief_unlocks, chips), color = MaterialTheme.colorScheme.primary)
            }
            Text(
                stringResource(R.string.lg_brief_controls),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hintShown) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        IconLightbulb(tint = MaterialTheme.colorScheme.tertiary)
                        Text(level.hint)
                    }
                }
            } else {
                TextButton(onClick = { hintShown = true }) {
                    IconLightbulb()
                    Text(stringResource(R.string.lg_show_hint))
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lg_start_building)) }
        }
    }
}
