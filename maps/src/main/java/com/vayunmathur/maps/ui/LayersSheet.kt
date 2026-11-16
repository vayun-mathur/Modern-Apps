package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconDirections
import com.vayunmathur.library.ui.IconShield
import com.vayunmathur.library.ui.IconStyle
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R

/**
 * Map-layers toggle sheet (P6), opened from the browse [LayersButton]. Only the
 * layers MA can actually support are surfaced, each gated on what exists:
 *
 *  - **Traffic** — GATED (no-op until the renderer can draw a raster overlay;
 *    see the GAP in [MapLayers]). The toggle is kept so the plumbing survives.
 *  - **Satellite** — GATED. Only shown when a raster tile source is hosted
 *    ([SatelliteSource.available]); hidden entirely otherwise (Decision D11).
 *  - **Safety layers** — GATED on the P13 PMTiles v5. The toggle is shown now but
 *    disabled (with a "coming soon" hint) until [SafetyLayersSource.available].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersSheet(
    onDismiss: () -> Unit,
    trafficEnabled: Boolean,
    onTrafficChange: (Boolean) -> Unit,
    satelliteEnabled: Boolean,
    onSatelliteChange: (Boolean) -> Unit,
    safetyEnabled: Boolean,
    onSafetyChange: (Boolean) -> Unit,
    transitEnabled: Boolean,
    onTransitChange: (Boolean) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.layers_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            )

            // Traffic — live.
            SettingsSwitchRow(
                title = stringResource(R.string.layer_traffic),
                supportingText = stringResource(R.string.layer_traffic_desc),
                checked = trafficEnabled,
                onCheckedChange = onTrafficChange,
                leadingContent = { IconWarning() },
            )

            // Satellite — only when a tile source is hosted.
            if (SatelliteSource.available) {
                SettingsSwitchRow(
                    title = stringResource(R.string.layer_satellite),
                    supportingText = stringResource(R.string.layer_satellite_desc),
                    checked = satelliteEnabled,
                    onCheckedChange = onSatelliteChange,
                    leadingContent = { IconStyle() },
                )
            }

            // Transit — offline rail lines from the on-device pack, live vehicle
            // pictograms (dedicated solid-badge sprites, not POI icons) and a
            // departure board for the stop nearest a tap. Stop pins
            // themselves are a tracked renderer gap ([TransitStopsLayer]).
            SettingsSwitchRow(
                title = stringResource(R.string.layer_transit),
                supportingText = stringResource(R.string.layer_transit_desc),
                checked = transitEnabled,
                onCheckedChange = onTransitChange,
                leadingContent = { IconDirections() },
            )

            // Safety layers — surfaced now, gated on the P13 PMTiles v5.
            SettingsSwitchRow(
                title = stringResource(R.string.layer_safety),
                supportingText = stringResource(
                    if (SafetyLayersSource.available) R.string.layer_safety_desc
                    else R.string.layer_safety_unavailable
                ),
                checked = safetyEnabled && SafetyLayersSource.available,
                onCheckedChange = onSafetyChange,
                enabled = SafetyLayersSource.available,
                leadingContent = { IconShield() },
            )
        }
    }
}
