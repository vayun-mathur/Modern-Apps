package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.library.ui.IconInstall
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.madeveloper.domain.CiStatus
import com.vayunmathur.madeveloper.domain.DevPresentation
import com.vayunmathur.madeveloper.domain.InstallPhase
import com.vayunmathur.madeveloper.platform.MaDevUiState

/** Pairing state and pending server-pushed installs. */
@Composable
fun PushScreen(state: MaDevUiState, onInstall: (String) -> Unit, onBack: () -> Unit) {
    AppScaffold(
        title = "Pushed installs",
        subtitle = if (state.connected) "Connected" else "Offline",
        onNavigateBack = onBack,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                DashboardSection(
                    title = "Pairing",
                    leadingIcon = { modifier, tint -> IconInstall(modifier, tint) },
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                when {
                                    state.paired -> "Paired"
                                    state.pairingCode.isNotBlank() -> "Pairing code: " + state.pairingCode
                                    else -> "Registering this device…"
                                },
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Text(
                                    if (state.connected) {
                                        "Socket connected to api.maproj.org"
                                    } else if (state.awaitingApproval) {
                                        "Waiting for approval in the server's /dev panel"
                                    } else {
                                        "Socket offline; reconnecting with backoff"
                                    },
                                )
                                Text("Approve the pairing code in the server's /dev panel to activate this device.")
                            }
                        },
                        trailingContent = {
                            StatusChip(
                                label = if (state.connected) "Connected" else "Offline",
                                status = if (state.connected) CiStatus.Passed else CiStatus.Unknown,
                            )
                        },
                    )
                }
            }
            item {
                DashboardSection(
                    title = "Pending installs",
                    leadingIcon = { modifier, tint -> IconInstall(modifier, tint) },
                ) {
                    if (state.pending.isEmpty()) {
                        Text(
                            "No pushed installs yet. Installs always need your tap; nothing installs silently.",
                            modifier = Modifier.padding(Spacing.lg),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.pending.forEachIndexed { index, pending ->
                        if (index > 0) DashboardSectionDivider()
                        Column {
                            ListItem(
                                headlineContent = { Text(pending.pkg) },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                        Text(
                                            DevPresentation.installPhaseLabel(pending.phase) +
                                                " · version " + pending.versionCode,
                                        )
                                        pending.error?.let {
                                            Text(it, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                },
                                leadingContent = { IconInstall() },
                                trailingContent = {
                                    StatusChip(
                                        label = DevPresentation.installPhaseLabel(pending.phase),
                                        status = when (pending.phase) {
                                            InstallPhase.READY, InstallPhase.DONE -> CiStatus.Passed
                                            InstallPhase.FAILED -> CiStatus.Failed
                                            InstallPhase.DOWNLOADING, InstallPhase.VERIFYING,
                                            InstallPhase.INSTALLING,
                                            -> CiStatus.Running

                                            InstallPhase.PUSHED -> CiStatus.Unknown
                                        },
                                    )
                                },
                            )
                            if (pending.phase == InstallPhase.DOWNLOADING ||
                                pending.phase == InstallPhase.VERIFYING
                            ) {
                                LinearProgressIndicator(
                                    progress = { pending.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                                )
                            }
                            if (pending.phase == InstallPhase.READY) {
                                Button(
                                    onClick = { onInstall(pending.pkg) },
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                                ) { Text("Install") }
                            }
                        }
                    }
                }
            }
        }
    }
}
