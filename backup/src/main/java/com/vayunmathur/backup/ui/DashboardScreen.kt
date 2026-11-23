package com.vayunmathur.backup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.backup.R
import com.vayunmathur.backup.platform.BackupUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.backup.ui.components.BackendSection
import com.vayunmathur.backup.ui.components.destinationLabel
import com.vayunmathur.library.ui.Text

/** Main screen once set up: status, per-category toggles, run-now, and restore. */
@Composable
fun DashboardScreen(
    state: BackupUiState,
    onPickFolder: () -> Unit,
    onSetWebDav: (String, String, String) -> Unit,
    onAppBackupToggle: (Boolean) -> Unit,
    onFileBackupToggle: (Boolean) -> Unit,
    onBackupNow: () -> Unit,
    onRestoreNow: () -> Unit,
) {
    AppScaffold(title = stringResource(R.string.app_name), isRefreshing = state.busy) { padding ->
        // Letterboxed on expanded windows so the form keeps a readable measure.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (state.busy) {
                Row {
                    Text(stringResource(R.string.dashboard_working))
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.dashboard_status), style = MaterialTheme.typography.titleMedium)
                    Text(state.settings.destinationLabel(LocalContext.current))
                    Text(
                        if (state.settings.lastRun > 0) stringResource(
                            R.string.dashboard_last_run,
                            state.settings.lastRun.asDateTime(),
                        )
                        else stringResource(R.string.dashboard_no_backup_yet),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
                    Text(
                        stringResource(R.string.dashboard_what_to_back_up),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.dashboard_app_data)) },
                        supportingContent = { Text(stringResource(R.string.dashboard_app_data_hint)) },
                        trailingContent = {
                            Switch(checked = state.settings.appBackupEnabled, onCheckedChange = onAppBackupToggle)
                        },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.dashboard_media)) },
                        supportingContent = { Text(stringResource(R.string.dashboard_media_hint)) },
                        trailingContent = {
                            Switch(checked = state.settings.fileBackupEnabled, onCheckedChange = onFileBackupToggle)
                        },
                    )
                }
            }

            Button(
                onClick = onBackupNow,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.dashboard_backup_now)) }

            OutlinedButton(
                onClick = onRestoreNow,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.dashboard_restore_files)) }

            BackendSection(state.settings, onPickFolder, onSetWebDav)
            }
        }
    }
}

private fun Long.asDateTime(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(this))
