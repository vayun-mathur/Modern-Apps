package com.vayunmathur.things.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.things.R
import com.vayunmathur.things.platform.BleManager
import com.vayunmathur.things.platform.ScaleBleManager

/**
 * Purely an "add a device" screen: one button per supported device type. Everything about a device
 * you already own — status, readings, forgetting it — lives on [HomePage] instead, so this screen
 * has nothing to say once both devices are paired.
 *
 * Picking happens in a dialog rather than inline, so the page itself stays two buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesPage(
    scanning: Boolean,
    discoveredDevices: List<BleManager.BleDevice>,
    scaleScanning: Boolean,
    scaleDevices: List<ScaleBleManager.ScaleBleDevice>,
    onScanClick: () -> Unit,
    onDeviceClick: (BleManager.BleDevice) -> Unit,
    onScaleScanClick: () -> Unit,
    onScaleDeviceClick: (ScaleBleManager.ScaleBleDevice) -> Unit,
    onNavigateBack: (() -> Unit)?,
) {
    // Which picker is open, if any. Null closes both.
    var picking by remember { mutableStateOf<String?>(null) }

    AppScaffold(
        title = stringResource(R.string.add_device),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        // Two buttons; cap them at a readable measure on desktop.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = {
                    picking = PICK_BOTTLE
                    onScanClick()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp),
            ) {
                Text(stringResource(R.string.connect_new_bottle), style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = {
                    picking = PICK_SCALE
                    onScaleScanClick()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp),
            ) {
                Text(stringResource(R.string.connect_new_scale), style = MaterialTheme.typography.titleMedium)
            }
        }
        }
    }

    when (picking) {
        PICK_BOTTLE -> DevicePickerDialog(
            searching = scanning,
            entries = discoveredDevices.map { it.name to it.address },
            onPick = { address ->
                discoveredDevices.firstOrNull { it.address == address }?.let(onDeviceClick)
                picking = null
            },
            onDismiss = { picking = null },
        )
        PICK_SCALE -> DevicePickerDialog(
            searching = scaleScanning,
            entries = scaleDevices.map { it.name to it.address },
            onPick = { address ->
                scaleDevices.firstOrNull { it.address == address }?.let(onScaleDeviceClick)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun DevicePickerDialog(
    searching: Boolean,
    entries: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_device)) },
        text = {
            if (entries.isEmpty()) {
                Text(
                    stringResource(if (searching) R.string.searching else R.string.no_devices_found),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(entries) { _, entry ->
                        val (name, address) = entry
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text(address) },
                            modifier = Modifier.clickable { onPick(address) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}

private const val PICK_BOTTLE = "bottle"
private const val PICK_SCALE = "scale"
