package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import com.vayunmathur.library.ui.AppPullToRefreshBox
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.appstore.platform.UpdatesUiState

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

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
internal fun UpdateRow(
    app: UnifiedApp,
    state: UpdatesUiState,
    onAppClick: (UnifiedApp) -> Unit,
    onInstall: () -> Unit,
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
            Button(
                onClick = onInstall,
                enabled = stage == null || stage is InstallStage.Failed,
            ) {
                Text(stringResource(R.string.action_update))
            }
        },
        // Library keys the same app with the same key, but the tabs are a
        // pager that only composes the settled page, so the two are never
        // origins at the same time.
        sharedKey = "appstore-app-${app.packageName}",
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
