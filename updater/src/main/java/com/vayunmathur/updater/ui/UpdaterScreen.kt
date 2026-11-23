package com.vayunmathur.updater.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.updater.R
import com.vayunmathur.updater.platform.UpdaterUiState

/** Current build, whether an update is waiting, and the two settings. */
@Composable
fun UpdaterScreen(
    state: UpdaterUiState,
    onCheckNow: () -> Unit,
    onAutoInstallChanged: (Boolean) -> Unit,
    onMeteredAllowedChanged: (Boolean) -> Unit,
) {
    val scrollBehavior = appBarScrollBehavior()
    AppScaffold(
        title = stringResource(R.string.app_name),
        scrollBehavior = scrollBehavior,
    ) { padding ->
        // Letterboxed on expanded windows so the status card keeps a readable measure.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = statusHeadline(state),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (state.currentBuild.isEmpty()) {
                            stringResource(R.string.build_unknown)
                        } else {
                            stringResource(R.string.current_build, state.currentBuild)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.lastFailure?.let { reason ->
                        Spacer(Modifier.height(8.dp))
                        // Shown verbatim: "could not reach <url>" and "server did not return a
                        // metadata line" are different problems, and the second one is ours.
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (state.checking) {
                        Text("Loading\u2026")
                    } else {
                        Button(onClick = onCheckNow) {
                            Text(stringResource(R.string.check_now))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            ListItem(
                headlineContent = { Text(stringResource(R.string.auto_install)) },
                supportingContent = { Text(stringResource(R.string.auto_install_desc)) },
                trailingContent = {
                    Switch(checked = state.autoInstall, onCheckedChange = onAutoInstallChanged)
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.metered_allowed)) },
                supportingContent = { Text(stringResource(R.string.metered_allowed_desc)) },
                trailingContent = {
                    Switch(
                        checked = state.meteredAllowed,
                        onCheckedChange = onMeteredAllowedChanged,
                        enabled = state.autoInstall,
                    )
                },
            )
            }
        }
    }
}

@Composable
private fun statusHeadline(state: UpdaterUiState): String = when {
    state.checking -> stringResource(R.string.checking)
    state.available != null -> stringResource(R.string.update_available, state.available.build)
    state.lastFailure != null -> stringResource(R.string.check_failed)
    state.lastCheckedMillis > 0L -> stringResource(R.string.up_to_date)
    else -> stringResource(R.string.not_checked_yet)
}
