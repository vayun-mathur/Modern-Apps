package com.vayunmathur.findfamily.ui.dialogs

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.platform.TrackerBinder
import com.vayunmathur.findfamily.platform.TrackerProvisioner
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.launch

/**
 * Dev-only dialog to bind a new custom UWB tracker. Scans for trackers in pairing
 * mode ([TrackerProvisioner.unprovisioned]), lets the user name and pick one, then
 * runs the full bind ([TrackerBinder.bind]). Gated at the call site by
 * `BuildConfig.DEV_BUILD` (see the FAB menu in MainPage).
 */
@SuppressLint("MissingPermission")
@Composable
fun AddTrackerDialog(backStack: NavBackStack<Route>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var binding by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val devices = remember { mutableStateListOf<BluetoothDevice>() }

    LaunchedEffect(Unit) {
        runCatching {
            TrackerProvisioner(context).unprovisioned().collect { d ->
                if (devices.none { it.address == d.address }) devices.add(d)
            }
        }
    }

    Dialog({ if (!binding) backStack.pop() }) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.add_tracker_title), style = MaterialTheme.typography.headlineMedium)

                OutlinedTextField(
                    name, { name = it },
                    label = { Text(stringResource(R.string.add_tracker_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    stringResource(R.string.add_tracker_scan_hint),
                    style = MaterialTheme.typography.bodySmall,
                )

                if (devices.isEmpty()) {
                    Text(stringResource(R.string.add_tracker_scanning), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(devices, key = { it.address }) { device ->
                            val label = runCatching { device.name }.getOrNull() ?: device.address
                            Card(Modifier.clickable(enabled = !binding && name.isNotBlank()) {
                                failed = false
                                binding = true
                                scope.launch {
                                    val ok = TrackerBinder.bind(context, name, device)
                                    binding = false
                                    if (ok) backStack.pop() else failed = true
                                }
                            }) {
                                ListItem(
                                    { Text(label) },
                                    colors = ListItemDefaults.colors(),
                                    supportingContent = { Text(device.address) },
                                )
                            }
                        }
                    }
                }

                if (binding) {
                    Text(stringResource(R.string.add_tracker_binding), style = MaterialTheme.typography.bodyMedium)
                }
                if (failed) {
                    Text(
                        stringResource(R.string.add_tracker_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Button({ if (!binding) backStack.pop() }, enabled = !binding) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    }
}
