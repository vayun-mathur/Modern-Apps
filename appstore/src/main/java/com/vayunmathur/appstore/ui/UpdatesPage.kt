package com.vayunmathur.appstore.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
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
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.appstore.util.UpdatesActions
import com.vayunmathur.appstore.util.UpdatesUiState
import com.vayunmathur.library.ui.AppPullToRefreshBox
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconVisibilityOff
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/** Binds [AppStoreViewModel] to the stateless [UpdatesScreen]. */
@Composable
fun UpdatesPage(viewModel: AppStoreViewModel, onAppClick: (UnifiedApp) -> Unit) {
    val state by viewModel.updatesUi.collectAsState()
    // Opening the tab is the request. The old screen made the user tap "Sync F-Droid" and
    // then "Check Play" by hand, which is not a thing anyone remembers to do.
    LaunchedEffect(Unit) {
        if (state.lastCheckedAt == 0L) viewModel.checkForUpdates()
    }
    UpdatesScreen(state = state, actions = viewModel, onAppClick = onAppClick)
}

@Composable
fun UpdatesScreen(
    state: UpdatesUiState,
    actions: UpdatesActions,
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    val anyInstalling = state.stages.values.any { it !is InstallStage.Failed }
    val total = state.updates.size + state.foreignUpdates.size

    Scaffold { padding ->
        AppPullToRefreshBox(
            isRefreshing = state.isChecking,
            onRefresh = { actions.checkForUpdates() },
            modifier = Modifier.fillMaxSize(),
            // Below the status bar: the scaffold's top inset is the exact clearance.
            topClearance = padding.calculateTopPadding(),
        ) {
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

                val status = state.statusMessage.takeIf { it.isNotBlank() }
                    ?: state.lastCheckedAt.takeIf { it > 0 }?.let {
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

                if (total == 0 && state.ignoredUpdates.isEmpty()) {
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
                        if (total > 0) {
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
                                    onIgnore = { actions.setUpdateIgnored(app.packageName, true) },
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
                                        onIgnore = { actions.setUpdateIgnored(app.packageName, true) },
                                    )
                                }
                            }
                        }
                        if (state.ignoredUpdates.isNotEmpty()) {
                            item("ignored-header") {
                                SectionHeader(
                                    title = stringResource(R.string.updates_ignored_title),
                                    subtitle = stringResource(R.string.updates_ignored_subtitle),
                                )
                            }
                            items(state.ignoredUpdates, key = { "ignored-${it.packageName}" }) { app ->
                                IgnoredRow(
                                    app = app,
                                    state = state,
                                    onAppClick = onAppClick,
                                    onUnignore = { actions.setUpdateIgnored(app.packageName, false) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpdateRow(
    app: UnifiedApp,
    state: UpdatesUiState,
    onAppClick: (UnifiedApp) -> Unit,
    onInstall: () -> Unit,
    onIgnore: () -> Unit,
) {
    val stage = state.stages[app.packageName]
    AppRow(
        app = app,
        isInstalled = true,
        stage = stage,
        installedIcon = state.installedIcons[app.packageName],
        versionLabel = versionLabel(app, state),
        onClick = { onAppClick(app) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onIgnore) { IconVisibilityOff() }
                Button(
                    onClick = onInstall,
                    enabled = stage == null || stage is InstallStage.Failed,
                ) {
                    Text(stringResource(R.string.action_update))
                }
            }
        },
        // Library keys the same app with the same key, but the tabs are a
        // pager that only composes the settled page, so the two are never
        // origins at the same time.
        sharedKey = "appstore-app-${app.packageName}",
    )
}

@Composable
private fun IgnoredRow(
    app: UnifiedApp,
    state: UpdatesUiState,
    onAppClick: (UnifiedApp) -> Unit,
    onUnignore: () -> Unit,
) {
    AppRow(
        app = app,
        isInstalled = true,
        stage = state.stages[app.packageName],
        installedIcon = state.installedIcons[app.packageName],
        versionLabel = versionLabel(app, state),
        onClick = { onAppClick(app) },
        trailing = {
            TextButton(onClick = onUnignore) {
                Text(stringResource(R.string.action_show_updates))
            }
        },
        sharedKey = null,
    )
}

@Composable
private fun versionLabel(app: UnifiedApp, state: UpdatesUiState): String? {
    val installed = state.installedInfos[app.packageName] ?: return null
    val old = installed.versionName?.let { "$it (${installed.versionCode})" }
        ?: installed.versionCode.toString()
    val new = app.versionName?.let { "$it (${app.versionCode})" }
        ?: app.versionCode.toString()
    return stringResource(R.string.version_update, old, new)
}
