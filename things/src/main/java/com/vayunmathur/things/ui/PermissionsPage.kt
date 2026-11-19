package com.vayunmathur.things.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconBluetooth
import com.vayunmathur.library.ui.IconHealth
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.things.R

/** Whether a permission the app needs has been granted, and if not, what can be done about it. */
enum class PermissionState {
    Granted,

    /** Not granted yet; asking in-app will show the system prompt. */
    Needed,

    /**
     * Asking again does nothing — either the user permanently denied it, or Health Connect is
     * missing from the device. Only system settings can resolve it.
     */
    Blocked,
}

/**
 * First-run gate. The app is a bridge between two BLE devices and Health Connect, so without
 * Bluetooth it cannot read anything and without Health Connect it has nowhere to put what it
 * reads — neither permission is optional, and there is no useful UI to show until both are in
 * place.
 *
 * Each permission is requested separately because they use different contracts: Bluetooth is an
 * ordinary runtime permission, Health Connect has its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsPage(
    bluetooth: PermissionState,
    healthConnect: PermissionState,
    onRequestBluetooth: () -> Unit,
    onRequestHealthConnect: () -> Unit,
    onResolveBluetooth: () -> Unit,
    onResolveHealthConnect: () -> Unit,
) {
    AppScaffold(
        title = stringResource(R.string.app_name),
    ) { padding ->
        // First-run gate; letterboxed on expanded windows like the rest of the app.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.permissions_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.permissions_rationale),
                style = MaterialTheme.typography.bodyMedium,
            )

            PermissionCard(
                state = bluetooth,
                name = stringResource(R.string.permission_bluetooth),
                reason = stringResource(R.string.permission_bluetooth_reason),
                onRequest = onRequestBluetooth,
                onResolve = onResolveBluetooth,
                icon = { IconBluetooth() },
            )
            PermissionCard(
                state = healthConnect,
                name = stringResource(R.string.permission_health_connect),
                reason = stringResource(R.string.permission_health_connect_reason),
                onRequest = onRequestHealthConnect,
                onResolve = onResolveHealthConnect,
                icon = { IconHealth() },
            )
        }
        }
    }
}

@Composable
private fun PermissionCard(
    state: PermissionState,
    name: String,
    reason: String,
    onRequest: () -> Unit,
    onResolve: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon()
                Text(name, style = MaterialTheme.typography.titleMedium)
            }
            Text(reason, style = MaterialTheme.typography.bodyMedium)
            when (state) {
                PermissionState.Granted -> Text(
                    stringResource(R.string.permission_granted),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                PermissionState.Needed -> Button(onClick = onRequest) {
                    Text(stringResource(R.string.permission_grant))
                }
                // An in-app request is a no-op once permanently denied, so send them somewhere
                // that can actually change the answer.
                PermissionState.Blocked -> {
                    Text(
                        stringResource(R.string.permission_blocked),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onResolve) {
                        Text(stringResource(R.string.permission_open_settings))
                    }
                }
            }
        }
    }
}
