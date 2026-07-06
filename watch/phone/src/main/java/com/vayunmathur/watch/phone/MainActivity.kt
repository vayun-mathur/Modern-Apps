package com.vayunmathur.watch.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.vayunmathur.watch.phone.ble.ConnectionState
import com.vayunmathur.watch.phone.health.HealthConnectManager
import com.vayunmathur.watch.phone.sync.SyncForegroundService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    SyncScreen(Modifier.padding(padding))
                }
            }
        }
    }

    @Composable
    private fun SyncScreen(modifier: Modifier) {
        val context = this
        val health = remember { HealthConnectManager(context) }

        val connection by SyncForegroundService.connectionStateFlow.collectAsState()
        val synced by SyncForegroundService.syncedCountFlow.collectAsState()

        var hasBlePerms by remember { mutableStateOf(false) }
        var hasHcPerms by remember { mutableStateOf(false) }
        var hcAvailable by remember { mutableStateOf(true) }

        val blePermsLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { result -> hasBlePerms = result.values.all { it } },
        )

        val hcPermsLauncher = rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract(),
            onResult = { granted -> hasHcPerms = granted.containsAll(health.permissions) },
        )

        LaunchedEffect(Unit) {
            hasBlePerms = blePermissions().all {
                checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
            hcAvailable = health.isAvailable()
            if (hcAvailable) hasHcPerms = health.hasAllPermissions()
        }

        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.status_connection, connection.label()))
            Text(stringResource(R.string.status_synced, synced))

            when {
                !hasBlePerms -> Button(onClick = { blePermsLauncher.launch(blePermissions()) }) {
                    Text(stringResource(R.string.grant_ble_permissions))
                }
                !hcAvailable -> Button(onClick = { openHealthConnectInstall() }) {
                    Text(stringResource(R.string.install_health_connect))
                }
                !hasHcPerms -> Button(onClick = { hcPermsLauncher.launch(health.permissions) }) {
                    Text(stringResource(R.string.grant_hc_permissions))
                }
                else -> {
                    Button(onClick = { SyncForegroundService.start(context) }) {
                        Text(stringResource(R.string.scan_pair))
                    }
                    Button(onClick = { SyncForegroundService.stop(context) }) {
                        Text(stringResource(R.string.stop_sync))
                    }
                }
            }
        }
    }

    @Composable
    private fun ConnectionState.label(): String = when (this) {
        ConnectionState.Disconnected -> stringResource(R.string.state_disconnected)
        ConnectionState.Scanning -> stringResource(R.string.state_scanning)
        ConnectionState.Connecting -> stringResource(R.string.state_connecting)
        ConnectionState.Connected -> stringResource(R.string.state_connected)
    }

    private fun blePermissions(): Array<String> = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private fun openHealthConnectInstall() {
        val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"),
                ),
            )
        }
    }
}
