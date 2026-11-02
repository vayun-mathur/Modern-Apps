package com.vayunmathur.securemail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.securemail.platform.MailUiState

/** Settings tab: identity, relay status, public-key export, forget. */
@Composable
fun SettingsScreen(
    state: MailUiState,
    onExportPublicKey: () -> Unit,
    onForget: () -> Unit,
    onDismissMessages: () -> Unit = {},
) {
    val messenger = rememberMessenger()
    ConsumeMessage(state.message, messenger, onDismissMessages)
    AppScaffold(title = "Settings", scrollBehavior = appBarScrollBehavior()) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Identity", style = MaterialTheme.typography.titleMedium)
                        Text(state.address, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Key: ${state.fingerprintHex.take(24)}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(
                            if (state.socketConnected) "Relay: connected" else "Relay: offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Button(onClick = onExportPublicKey, modifier = Modifier.fillMaxWidth()) {
                    Text("Show public key")
                }
                state.exportArmor?.let { armor ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            armor.take(600) + if (armor.length > 600) "…" else "",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Button(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                    Text("Forget identity on this device")
                }
            }
        }
    }
}
