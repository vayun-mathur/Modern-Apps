package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R

import com.vayunmathur.appstore.platform.UpdatesActions
import com.vayunmathur.appstore.platform.UpdatesUiState
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text

@Composable
fun UpdatesScreen(
    state: UpdatesUiState,
    actions: UpdatesActions,
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    val anyInstalling = state.stages.values.any { it !is InstallStage.Failed }
    val total = state.updates.size + state.foreignUpdates.size

    AppScaffold(
        topBar = {},
        isRefreshing = state.isChecking,
        onRefresh = { actions.checkForUpdates() },
    ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.updates.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { actions.updateAll() },
                            enabled = !anyInstalling,
                            modifier = Modifier.weight(1f),
                        ) {
                            IconDownload()
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.update_all, state.updates.size))
                        }
                    }
                }

                // Keep the checking status visible in content: content now stays
                // composed under the scaffold's top refresh indicator.
                val status = if (state.isChecking) {
                    state.statusMessage.ifBlank { stringResource(R.string.updates_checking) }
                } else {
                    state.statusMessage.takeIf { it.isNotBlank() }
                } ?: state.lastCheckedAt.takeIf { it > 0 }?.let {
                        stringResource(
                            R.string.updates_last_checked,
                            DateUtils.getRelativeTimeSpanString(
                                it,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ),
                        )
                    }
                if (status != null) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                if (total == 0 && state.isChecking) {
                    // First check in flight: empty placeholder under the top
                    // indicator, never the up-to-date empty state.
                    Box(Modifier.fillMaxSize())
                } else if (total == 0) {
                    EmptyState(
                        title = stringResource(R.string.all_apps_up_to_date),
                        message = stringResource(R.string.updates_empty_message),
                        icon = { IconCheck() },
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        item("count") {
                            Text(
                                pluralStringResource(
                                    R.plurals.updates_count,
                                    total,
                                    total,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(state.updates, key = { it.packageName }) { app ->
                            UpdateRow(
                                app = app,
                                state = state,
                                onAppClick = onAppClick,
                                onInstall = { actions.install(app) },
                            )
                        }
                        if (state.foreignUpdates.isNotEmpty()) {
                            item("foreign-header") {
                                SectionHeader(
                                    title = stringResource(R.string.updates_foreign_title),
                                    subtitle = stringResource(R.string.updates_foreign_subtitle),
                                )
                            }
                            items(state.foreignUpdates, key = { "foreign-${it.packageName}" }) { app ->
                                UpdateRow(
                                    app = app,
                                    state = state,
                                    onAppClick = onAppClick,
                                    onInstall = { actions.install(app) },
                                )
                            }
                        }
                    }
                }
            }
    }
}
