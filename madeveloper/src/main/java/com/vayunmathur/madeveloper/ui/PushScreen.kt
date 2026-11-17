package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconInstall
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.madeveloper.domain.InstallPhase
import com.vayunmathur.madeveloper.platform.MaDevUiState

/** Pairing state and pending server-pushed installs. */
@Composable
fun PushScreen(state: MaDevUiState, onInstall: (String) -> Unit, onBack: () -> Unit) {
    AppScaffold(title = "Pushed installs", onNavigateBack = onBack, scrollBehavior = appBarScrollBehavior()) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            when {
                                state.paired -> "Paired"
                                state.pairingCode.isNotBlank() -> "Pairing code: " + state.pairingCode
                                else -> "Registering this device…"
                            },
                        )
                        Text(
                            when {
                                state.connected -> "Socket connected to api.maproj.org"
                                state.awaitingApproval -> "Waiting for approval in the server's /dev panel"
                                else -> "Socket offline; reconnecting with backoff"
                            },
                        )
                        Text("Approve the pairing code in the server's /dev panel to activate this device.")
                    }
                }
            }
            items(state.pending) { pending ->
                ListItem(
                    headlineContent = { Text(pending.pkg) },
                    supportingContent = { Text(pending.phase.name + " · version " + pending.versionCode + (pending.error?.let { " · " + it } ?: "")) },
                    leadingContent = { IconInstall() },
                )
                if (pending.phase == InstallPhase.READY) {
                    Button(onClick = { onInstall(pending.pkg) }, modifier = Modifier.fillMaxWidth()) { Text("Install") }
                }
            }
            if (state.pending.isEmpty()) item { Text("No pushed installs yet. Installs always need your tap; nothing installs silently.") }
        }
    }
}
