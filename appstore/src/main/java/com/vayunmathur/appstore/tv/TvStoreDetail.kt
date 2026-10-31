package com.vayunmathur.appstore.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.ui.formatSize
import com.vayunmathur.library.tv.TvBadge
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvSwitchRow
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.R as UiR

/**
 * One app's TV page: two panes side by side, no backdrop. Left is identity plus
 * actions — icon, name, developer, source badge, Install/Update/Open and the
 * uninstall secondary, then the Size/Rated/Version stats. Right is the scrolling
 * body — summary, description, What's new, chips, the security card, screenshots,
 * links. Same order and same strings as the phone's detail page.
 */
@Composable
fun TvStoreDetail(
    viewModel: TvStoreViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.detail.collectAsState()
    BackHandler(onBack = onBack)
    TvStoreDetailScreen(
        state = state,
        onInstall = viewModel::install,
        onOpen = viewModel::openApp,
        onUninstall = viewModel::uninstallApp,
        onOpenInBrowser = viewModel::openInBrowser,
        onOpenInPlayStore = viewModel::openInPlayStore,
        onDismissFailure = viewModel::dismissInstallFailure,
        onSetIgnored = viewModel::setUpdateIgnored,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun TvStoreDetailScreen(
    state: TvDetailUiState,
    modifier: Modifier = Modifier,
    onInstall: (UnifiedApp) -> Unit = {},
    onOpen: (String) -> Unit = {},
    onUninstall: (String) -> Unit = {},
    onOpenInBrowser: (String) -> Unit = {},
    onOpenInPlayStore: (String) -> Unit = {},
    onDismissFailure: (String) -> Unit = {},
    onSetIgnored: (String, Boolean) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
) {
    val app = state.app
    val primaryFocus = remember(app?.packageName) { FocusRequester() }
    var showUninstallConfirm by remember(app?.packageName) { mutableStateOf(false) }
    if (app == null) {
        TvEmptyState(
            title = stringResource(R.string.home_empty_title),
            icon = { IconPackage() },
            action = { TvButton(text = stringResource(R.string.tv_detail_back), onClick = onBack) },
            modifier = modifier,
        )
        return
    }
    TvInitialFocus(primaryFocus, app.packageName)
    Column(modifier.fillMaxSize()) {
        TvButton(
            text = stringResource(R.string.tv_detail_back),
            onClick = onBack,
            icon = { IconBack() },
            modifier = Modifier.padding(start = tvContentStart(), top = 20.dp, bottom = 8.dp),
        )
        Row(
            Modifier.fillMaxSize().padding(start = tvContentStart(), end = 48.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            TvStoreDetailIdentity(
                state = state,
                focusRequester = primaryFocus,
                onInstall = { onInstall(app) },
                onOpen = { onOpen(app.packageName) },
                onUninstall = { showUninstallConfirm = true },
                onDismissFailure = { onDismissFailure(app.packageName) },
                onSetIgnored = { onSetIgnored(app.packageName, it) },
                modifier = Modifier.weight(0.65f).fillMaxHeight(),
            )
            TvStoreDetailBody(
                state = state,
                onOpenInBrowser = onOpenInBrowser,
                onOpenInPlayStore = onOpenInPlayStore,
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            )
        }
    }
    if (showUninstallConfirm) {
        TvDialog(
            title = stringResource(R.string.uninstall_3, app.name),
            body = stringResource(
                R.string.this_will_uninstall_you_can_reinstall_la, app.packageName
            ),
            onDismissRequest = { showUninstallConfirm = false },
        ) {
            TvButton(
                text = stringResource(UiR.string.cancel),
                onClick = { showUninstallConfirm = false },
            )
            TvButton(
                text = stringResource(R.string.uninstall),
                onClick = {
                    showUninstallConfirm = false
                    onUninstall(app.packageName)
                },
                primary = true,
            )
        }
    }
}

/**
 * Left pane: icon, name, developer, source badge, primary Install/Update/Open
 * plus the Open/Uninstall secondary exactly like the phone, then the stats row.
 */
@Composable
private fun TvStoreDetailIdentity(
    state: TvDetailUiState,
    focusRequester: FocusRequester,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onUninstall: () -> Unit,
    onDismissFailure: () -> Unit,
    onSetIgnored: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = state.app ?: return
    val stage = state.stage
    val busy = stage != null && stage !is InstallStage.Failed
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(contentAlignment = Alignment.Center) {
            TvStoreAppArt(app = app, installedIcon = state.installedIcon, size = 120.dp)
        }
        Text(
            text = app.name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        app.author?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvBadge(text = app.tvSourceLabel())
            if (app.reproducible) TvBadge(text = stringResource(R.string.chip_reproducible))
            if (app.rating != null) {
                TvBadge(
                    text = String.format(java.util.Locale.US, "%.1f ★", app.rating),
                )
            }
        }
        // Primary action: exactly the phone's AppInstallActions logic.
        when {
            busy -> TvButton(
                text = stage.tvStageLabel(),
                onClick = {},
                enabled = false,
                modifier = Modifier.focusRequester(focusRequester),
            )
            state.hasUpdate -> {
                TvButton(
                    text = stringResource(R.string.action_update),
                    onClick = onInstall,
                    primary = true,
                    icon = { IconDownload() },
                    modifier = Modifier.focusRequester(focusRequester),
                )
                TvButton(
                    text = stringResource(UiR.string.open),
                    onClick = onOpen,
                )
            }
            state.isInstalled -> {
                TvButton(
                    text = stringResource(UiR.string.open),
                    onClick = onOpen,
                    primary = true,
                    modifier = Modifier.focusRequester(focusRequester),
                )
                TvButton(
                    text = stringResource(R.string.uninstall),
                    onClick = onUninstall,
                    icon = { IconDelete() },
                )
            }
            else -> TvButton(
                text = stringResource(R.string.action_install),
                onClick = onInstall,
                primary = true,
                icon = { IconDownload() },
                modifier = Modifier.focusRequester(focusRequester),
            )
        }
        if (state.isInstalled && state.installedInfo?.versionName != null && !state.hasUpdate) {
            Text(
                text = stringResource(R.string.installed_2, state.installedInfo.versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (stage is InstallStage.Failed) {
            Text(
                text = stringResource(R.string.stage_failed, stage.reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TvButton(
                text = stringResource(R.string.action_dismiss),
                onClick = onDismissFailure,
            )
        }
        // Per-app "ignore updates" (§885): same exclusion as the phone — Update
        // All, the count and notifications skip it, but Update above still works.
        TvStoreDetailIgnoreRow(
            appName = app.name,
            ignored = state.updateIgnored,
            visible = state.isInstalled,
            onSetIgnored = onSetIgnored,
        )
        // Stats row: Size / Rated / Version, like the phone's facts row.
        TvStoreDetailStats(app = app)
        TvStoreVerificationLine(state = state)
    }
}

/**
 * Per-app "ignore updates" toggle (§885). Its own function so the identity
 * pane stays under the complexity cap: excluded from Update All, the count
 * and notifications, but the Update button above still installs.
 */
@Composable
private fun TvStoreDetailIgnoreRow(
    appName: String,
    ignored: Boolean,
    visible: Boolean,
    onSetIgnored: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    TvSwitchRow(
        headline = stringResource(R.string.detail_ignore_updates, appName),
        checked = ignored,
        onCheckedChange = onSetIgnored,
        supporting = stringResource(R.string.detail_ignore_updates_summary),
        modifier = modifier,
    )
}

/** Size / Rated / Version in whatever subset the source published. Like the phone. */
@Composable
private fun TvStoreDetailStats(app: UnifiedApp, modifier: Modifier = Modifier) {
    val cells = buildList<Pair<String, String>> {
        if (app.sizeBytes > 0) add(stringResource(R.string.fact_size) to formatSize(app.sizeBytes))
        app.contentRating?.let { add(stringResource(R.string.fact_rated) to it) }
        app.versionName?.let { add(stringResource(R.string.fact_version) to it) }
    }
    if (cells.isEmpty()) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        cells.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** What the last install proved, in the verifier's own words — or the neutral line. */
@Composable
private fun TvStoreVerificationLine(state: TvDetailUiState, modifier: Modifier = Modifier) {
    val message = state.verification.toTvMessage()
    if (message == null) {
        Text(
            text = stringResource(R.string.detail_tap_for_trust),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.verification is VerificationResult.Rejected ||
            state.verification is VerificationResult.Unverified
        ) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier,
    )
}

@Composable
private fun VerificationResult?.toTvMessage(): String? = when (this) {
    is VerificationResult.Rejected ->
        stringResource(R.string.detail_install_blocked, reason)
    is VerificationResult.Verified ->
        stringResource(R.string.detail_checked_on_install, detail)
    is VerificationResult.Unverified ->
        stringResource(R.string.detail_installed_unverified, reason)
    null -> null
}
