package com.vayunmathur.securemail.ui

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.securemail.data.MailIdentity
import com.vayunmathur.securemail.platform.MailUiState

/** Settings tab: own addresses (primary + aliases), relay status, public-key export, forget. */
@Composable
fun SettingsScreen(
    state: MailUiState,
    identities: List<MailIdentity>,
    onCreateAlias: (label: String) -> Unit,
    onSetAliasDisabled: (address: String, disabled: Boolean) -> Unit,
    onExportPublicKey: () -> Unit,
    onForget: () -> Unit,
    onDismissMessages: () -> Unit = {},
) {
    val messenger = rememberMessenger()
    val context = LocalContext.current
    ConsumeMessage(state.message, messenger, onDismissMessages)
    var newLabel by remember { mutableStateOf("") }
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
                        Text("Addresses", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Mail to any address lands in this inbox, and you can send from any of them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        identities.forEach { identity ->
                            AddressRow(
                                identity = identity,
                                onCopy = { ExternalIntents.copyToClipboard(context, identity.address, "address") },
                                onSetDisabled = { onSetAliasDisabled(identity.address, it) },
                            )
                        }
                        OutlinedTextField(
                            value = newLabel,
                            onValueChange = { newLabel = it },
                            label = { Text("Label for new alias (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                onCreateAlias(newLabel)
                                newLabel = ""
                            },
                            enabled = !state.busy && state.socketConnected,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    state.busy -> "Creating…"
                                    !state.socketConnected -> "Relay offline"
                                    else -> "New random alias"
                                },
                            )
                        }
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

@Composable
private fun AddressRow(
    identity: MailIdentity,
    onCopy: () -> Unit,
    onSetDisabled: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            val title = when {
                identity.isPrimary -> "Primary"
                identity.label.isNotBlank() -> identity.label
                else -> "Alias"
            }
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(identity.address, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Key: ${identity.fingerprintHex.take(16)}…" +
                    if (identity.disabled) " · disabled, mail held" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        IconButton(onClick = onCopy) { IconCopy() }
        if (!identity.isPrimary) {
            Switch(checked = !identity.disabled, onCheckedChange = { onSetDisabled(!it) })
        }
    }
}
