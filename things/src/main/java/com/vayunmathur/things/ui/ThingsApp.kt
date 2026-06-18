package com.vayunmathur.things.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.things.util.BleManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThingsApp(
    totalMl: Int,
    goalMl: Int,
    messages: List<String>,
    connectionState: String,
    scanning: Boolean,
    discoveredDevices: List<BleManager.BleDevice>,
    onScanClick: () -> Unit,
    onDeviceClick: (BleManager.BleDevice) -> Unit,
    onDisconnectClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Hydration") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HydrationCard(totalMl = totalMl, goalMl = goalMl)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(connectionState, style = MaterialTheme.typography.titleMedium)
                if (connectionState == "Connected") {
                    OutlinedButton(onClick = onDisconnectClick) { Text("Disconnect") }
                } else {
                    Button(onClick = onScanClick, enabled = !scanning) { Text("Scan") }
                }
            }

            if (discoveredDevices.isNotEmpty() && connectionState != "Connected") {
                Text("Devices found:", style = MaterialTheme.typography.labelLarge)
                discoveredDevices.forEach { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceClick(device) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (messages.isNotEmpty()) {
                HorizontalDivider()
                Text("Today's drinks", style = MaterialTheme.typography.titleSmall)
            }

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(messages) { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HydrationCard(totalMl: Int, goalMl: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Today", style = MaterialTheme.typography.titleMedium)
            Text(
                "$totalMl mL",
                style = MaterialTheme.typography.displayMedium
            )
            Spacer(Modifier.height(12.dp))
            val progress = if (goalMl > 0) {
                (totalMl.toFloat() / goalMl).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Goal $goalMl mL",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
