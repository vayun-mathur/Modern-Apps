package com.vayunmathur.things.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconWidgets
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.things.R
import com.vayunmathur.things.platform.DeviceController.LinkState
import com.vayunmathur.things.platform.Sex

/**
 * The landing screen. Cards are shown for every *paired* device, not just the reachable ones — a
 * body scale is powered off between weigh-ins, so hiding it whenever it sleeps would mean the app
 * looked empty almost all the time.
 *
 * Health data (hydration volume, weight, body composition) is written to Health Connect and read by
 * the Health app; this app deliberately does not display any of it. What remains here is device
 * telemetry and the scale profile inputs, which feed the body-composition calc rather than being
 * health readouts themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(
    bottlePaired: Boolean,
    bottleLink: LinkState,
    bottleConnectionState: String,
    tempC: Int?,
    tds: Int?,
    batteryPct: Int?,
    charging: Boolean,
    volumePct: Int?,
    lastUpdatedMillis: Long?,
    scalePaired: Boolean,
    scaleLink: LinkState,
    scaleConnectionState: String,
    scaleUserSlot: Int?,
    scaleSex: Sex,
    scaleAge: String,
    scaleHeight: String,
    scaleAthlete: Boolean,
    onScaleSexChange: (Sex) -> Unit,
    onScaleAgeChange: (String) -> Unit,
    onScaleHeightChange: (String) -> Unit,
    onScaleAthleteChange: (Boolean) -> Unit,
    onForgetBottle: () -> Unit,
    onForgetScale: () -> Unit,
    onResetScale: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    AppScaffold(
        title = stringResource(R.string.app_name),
        scrollBehavior = appBarScrollBehavior(),
        actions = {
            IconButton(onClick = onOpenDevices) { IconWidgets() }
        },
    ) { padding ->
        // A two-card dashboard stretched across a desktop window is unreadable;
        // cap it at a readable measure.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (bottlePaired) {
                item {
                    BottleStatusCard(
                        link = bottleLink,
                        connectionState = bottleConnectionState,
                        tempC = tempC,
                        tds = tds,
                        batteryPct = batteryPct,
                        charging = charging,
                        volumePct = volumePct,
                        lastUpdatedMillis = lastUpdatedMillis,
                        onForget = onForgetBottle,
                    )
                }
            }

            if (scalePaired) {
                item {
                    ScaleCard(
                        link = scaleLink,
                        connectionState = scaleConnectionState,
                        userSlot = scaleUserSlot,
                        sex = scaleSex,
                        age = scaleAge,
                        height = scaleHeight,
                        athlete = scaleAthlete,
                        onSexChange = onScaleSexChange,
                        onAgeChange = onScaleAgeChange,
                        onHeightChange = onScaleHeightChange,
                        onAthleteChange = onScaleAthleteChange,
                        onForget = onForgetScale,
                        onReset = onResetScale,
                    )
                }
            }

            if (!bottlePaired && !scalePaired) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.no_devices),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Button(onClick = onOpenDevices) { Text(stringResource(R.string.add_device)) }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun LinkStatusText(link: LinkState, connectionState: String) {
    // The technical string is kept verbatim; only the emphasis is derived from the link state.
    Text(
        connectionState,
        style = MaterialTheme.typography.bodyMedium,
        color = if (link == LinkState.Connected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun BottleStatusCard(
    link: LinkState,
    connectionState: String,
    tempC: Int?,
    tds: Int?,
    batteryPct: Int?,
    charging: Boolean,
    volumePct: Int?,
    lastUpdatedMillis: Long?,
    onForget: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.bottle_status),
                style = MaterialTheme.typography.titleSmall
            )
            LinkStatusText(link, connectionState)
            val hasData = tempC != null || tds != null || batteryPct != null || volumePct != null
            if (!hasData) {
                Text(
                    stringResource(R.string.waiting_for_data),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                if (batteryPct != null) {
                    val battery = if (charging) {
                        stringResource(R.string.battery_charging, batteryPct)
                    } else {
                        stringResource(R.string.percent, batteryPct)
                    }
                    StatusRow(stringResource(R.string.label_battery), battery)
                }
                if (volumePct != null) {
                    StatusRow(stringResource(R.string.label_water_level), stringResource(R.string.percent, volumePct))
                }
                if (tempC != null) {
                    StatusRow(stringResource(R.string.label_temperature), stringResource(R.string.temperature_c, tempC))
                }
                if (tds != null) {
                    StatusRow(stringResource(R.string.label_purity), stringResource(R.string.purity_ppm, tds))
                }
            }
            if (lastUpdatedMillis != null) {
                val time = remember(lastUpdatedMillis) { formatClockTime(lastUpdatedMillis) }
                Text(
                    stringResource(R.string.last_updated, time),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            OutlinedButton(onClick = onForget) { Text(stringResource(R.string.forget_device)) }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatClockTime(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

@Composable
private fun ScaleCard(
    link: LinkState,
    connectionState: String,
    userSlot: Int?,
    sex: Sex,
    age: String,
    height: String,
    athlete: Boolean,
    onSexChange: (Sex) -> Unit,
    onAgeChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onAthleteChange: (Boolean) -> Unit,
    onForget: () -> Unit,
    onReset: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.scale_title), style = MaterialTheme.typography.titleSmall)
            LinkStatusText(link, connectionState)
            // The scale files each weigh-in under one of its eight slots; ours is what lets the
            // app pick out measurements taken while the phone was away.
            Text(
                if (userSlot != null) {
                    stringResource(R.string.scale_user_slot, userSlot)
                } else {
                    stringResource(R.string.scale_user_unregistered)
                },
                style = MaterialTheme.typography.bodySmall,
            )

            // The profile drives the body-composition maths, so it stays editable whether or not
            // the scale happens to be awake.
            Text(stringResource(R.string.scale_profile), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sex == Sex.Male, onClick = { onSexChange(Sex.Male) }, label = { Text(stringResource(R.string.sex_male)) })
                FilterChip(selected = sex == Sex.Female, onClick = { onSexChange(Sex.Female) }, label = { Text(stringResource(R.string.sex_female)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = age,
                    onValueChange = onAgeChange,
                    label = { Text(stringResource(R.string.age)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = onHeightChange,
                    label = { Text(stringResource(R.string.height_cm)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = athlete, onCheckedChange = onAthleteChange)
                Text(stringResource(R.string.athlete), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(R.string.syncs_to_health_connect),
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { confirmReset = true }) {
                Text(stringResource(R.string.scale_reset_users))
            }
            OutlinedButton(onClick = onForget) { Text(stringResource(R.string.forget_device)) }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.scale_reset_title),
            message = stringResource(R.string.scale_reset_message),
            confirmLabel = stringResource(UiR.string.reset),
            // Without an explicit dismiss label the dialog renders no cancel button, which is a
            // poor way to guard something destructive.
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
            onConfirm = {
                confirmReset = false
                onReset()
            },
            onDismiss = { confirmReset = false },
        )
    }
}
