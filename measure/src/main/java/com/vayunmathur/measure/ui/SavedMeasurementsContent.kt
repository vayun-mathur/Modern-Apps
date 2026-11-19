package com.vayunmathur.measure.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.measure.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.measure.data.model.MeasurementKind
import com.vayunmathur.measure.data.model.SavedMeasurement
import com.vayunmathur.measure.data.model.UnitSystem
import com.vayunmathur.measure.domain.Units
import com.vayunmathur.measure.platform.SavedActions
import com.vayunmathur.measure.platform.SavedUiState

@Composable
fun SavedMeasurementsContent(
    state: SavedUiState,
    actions: SavedActions,
    onBack: () -> Unit = {},
) {
    AppScaffold(
        title = stringResource(UiR.string.saved),
        onNavigateBack = onBack,
    ) { padding ->
        // Single-pane list; letterboxed on expanded windows so rows keep a
        // readable measure on desktop.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
        if (state.measurements.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.saved_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.saved_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.measurements, key = { it.id }) { m ->
                    SettingsRow(
                        title = m.label,
                        supportingText = formatMeasurement(m, state.unitSystem),
                        trailingContent = {
                            IconButton(onClick = { actions.delete(m.id) }) { IconDelete() }
                        },
                    )
                }
            }
        }
        }
    }
}

private fun formatMeasurement(m: SavedMeasurement, system: UnitSystem): String = when (m.kind) {
    MeasurementKind.Area -> Units.formatArea(m.value, system)
    MeasurementKind.Angle -> Units.formatAngle(m.value)
    else -> Units.formatLength(m.value, system)
}
