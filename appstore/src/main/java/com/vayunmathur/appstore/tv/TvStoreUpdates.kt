package com.vayunmathur.appstore.tv

import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.tv.tvGridHeader
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconDownload

/**
 * The TV updates hub: pending updates as a grid of full-information cards with
 * old → new version lines, a per-card Update action, and an "Update all" primary
 * action in the header.
 *
 * Mirrors the phone's UpdatesPage: opening the destination is the request, so the
 * first visit kicks off a check. Failures of an update-all run surface once via
 * AppMessages (see [TvStoreViewModel.updateAll]), not per row.
 */
@Composable
fun TvStoreUpdates(
    viewModel: TvStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.updatesUi.collectAsState()
    // Opening the tab is the request: the phone made the user tap "Sync F-Droid" then
    // "Check Play" by hand, which nobody remembers to do.
    LaunchedEffect(Unit) {
        if (state.lastCheckedAt == 0L) viewModel.checkForUpdates()
    }
    TvStoreUpdatesScreen(
        state = state,
        onCheckForUpdates = viewModel::checkForUpdates,
        onUpdateAll = viewModel::updateAll,
        onInstall = viewModel::install,
        onAppClick = onAppClick,
        onIgnore = viewModel::setUpdateIgnored,
        modifier = modifier,
    )
}

@Composable
internal fun TvStoreUpdatesScreen(
    state: TvUpdatesUiState,
    modifier: Modifier = Modifier,
    onCheckForUpdates: () -> Unit = {},
    onUpdateAll: () -> Unit = {},
    onInstall: (UnifiedApp) -> Unit = {},
    onAppClick: (UnifiedApp) -> Unit = {},
    onIgnore: (String, Boolean) -> Unit = { _, _ -> },
) {
    val primaryFocus = remember { FocusRequester() }
    val anyInstalling = state.stages.values.any { it !is InstallStage.Failed }
    val total = state.updates.size + state.foreignUpdates.size
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
    // Unkeyed: runs once per visit. Keying on the count would yank focus back to
    // "Update all" every time a check completes or an update installs and drops off.
    TvInitialFocus(primaryFocus)
    if (total == 0 && state.ignoredUpdates.isEmpty() && !state.isChecking) {
        TvEmptyState(
            title = stringResource(R.string.all_apps_up_to_date),
            body = stringResource(R.string.updates_empty_message),
            icon = { IconCheck() },
        )
        return
    }
    val countTitle = if (state.isChecking && total == 0) {
        stringResource(R.string.updates_checking)
    } else {
        pluralStringResource(R.plurals.updates_count, total, total)
    }
    // Resolved outside the grid scope: TvGrid's content lambda is a plain
    // LazyGridScope receiver, not a @Composable context, so stringResource calls
    // cannot live inside it.
    val foreignTitle = stringResource(R.string.updates_foreign_title)
    val foreignSubtitle = stringResource(R.string.updates_foreign_subtitle)
    val ignoredTitle = stringResource(R.string.updates_ignored_title)
    val ignoredSubtitle = stringResource(R.string.updates_ignored_subtitle)
    TvGrid(columns = 3, modifier = modifier.fillMaxSize()) {
        tvFullWidth(key = "header") {
            TvScreenHeader(
                title = stringResource(R.string.nav_updates),
                subtitle = status,
                actions = {
                    if (state.updates.isNotEmpty()) {
                        TvButton(
                            text = stringResource(R.string.update_all, state.updates.size),
                            onClick = onUpdateAll,
                            primary = true,
                            enabled = !anyInstalling,
                            icon = { IconDownload() },
                        )
                    }
                    TvButton(
                        text = if (state.isChecking) {
                            stringResource(R.string.updates_checking)
                        } else {
                            stringResource(R.string.action_check_again)
                        },
                        onClick = onCheckForUpdates,
                        enabled = !state.isChecking,
                    )
                },
            )
        }
        tvGridHeader(
            key = "count",
            title = countTitle,
            first = true,
        )
        items(state.updates, key = { it.packageName }) { app ->
            TvUpdateCard(
                app = app,
                state = state,
                onAppClick = onAppClick,
                onInstall = { onInstall(app) },
                onIgnore = { onIgnore(app.packageName, true) },
            )
        }
        if (state.foreignUpdates.isNotEmpty()) {
            tvGridHeader(
                key = "foreign",
                title = foreignTitle,
                subtitle = foreignSubtitle,
            )
            items(state.foreignUpdates, key = { "foreign-${it.packageName}" }) { app ->
                TvUpdateCard(
                    app = app,
                    state = state,
                    onAppClick = onAppClick,
                    onInstall = { onInstall(app) },
                    onIgnore = { onIgnore(app.packageName, true) },
                )
            }
        }
        if (state.ignoredUpdates.isNotEmpty()) {
            tvGridHeader(
                key = "ignored",
                title = ignoredTitle,
                subtitle = ignoredSubtitle,
            )
            items(state.ignoredUpdates, key = { "ignored-${it.packageName}" }) { app ->
                TvUpdateCard(
                    app = app,
                    state = state,
                    onAppClick = onAppClick,
                    onInstall = { onInstall(app) },
                    ignored = true,
                    onIgnore = { onIgnore(app.packageName, false) },
                )
            }
        }
    }
}

@Composable
private fun TvUpdateCard(
    app: UnifiedApp,
    state: TvUpdatesUiState,
    onAppClick: (UnifiedApp) -> Unit,
    onInstall: () -> Unit,
    ignored: Boolean = false,
    onIgnore: () -> Unit,
) {
    val installed = state.installedInfos[app.packageName]
    val old = installed?.let { info ->
        info.versionName?.let { "$it (${info.versionCode})" }
            ?: info.versionCode.toString()
    }
    val new = app.versionName?.let { "$it (${app.versionCode})" }
        ?: app.versionCode.toString()
    val versionLabel = old?.let { stringResource(R.string.version_update, it, new) }
    val stage = state.stages[app.packageName]
    val busy = stage != null && stage !is InstallStage.Failed
    // CENTER on the button updates that app; CENTER on the card opens its
    // page. Mirrors the phone's row + trailing Update button.
    TvStoreAppCard(
        app = app,
        onClick = { onAppClick(app) },
        installedIcon = state.installedIcons[app.packageName],
        isInstalled = true,
        stage = stage,
        versionLabel = versionLabel,
        trailing = {
            if (ignored) {
                TvButton(
                    text = stringResource(R.string.action_show_updates),
                    onClick = onIgnore,
                )
            } else {
                TvButton(
                    text = if (busy) {
                        stage.tvStageLabel()
                    } else {
                        stringResource(R.string.action_update)
                    },
                    onClick = { onInstall() },
                    primary = !busy,
                    enabled = !busy,
                )
            }
        },
    )
}
