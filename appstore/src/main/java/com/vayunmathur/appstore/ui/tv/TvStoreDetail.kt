package com.vayunmathur.appstore.ui.tv
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.platform.TvDetailUiState
import com.vayunmathur.appstore.platform.TvStoreViewModel
import com.vayunmathur.appstore.platform.loadDetail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.R as UiR

/**
 * One app's TV page: two panes side by side, no backdrop. Left is identity plus
 * actions — icon, name, developer, source badge, Install/Update/Open and the
 * uninstall secondary, then the Size/Rated/Version stats. Right is the scrolling
 * body — summary, description, What's new, chips, the security card, screenshots,
 * links. Same order and same strings as the phone's detail page.
 */
private const val DETAIL_IDENTITY_WEIGHT = 0.65f

@Composable
fun TvStoreDetail(
    viewModel: TvStoreViewModel,
    packageName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The Detail route's argument, reloaded whenever it changes so a
    // restored entry shows its own app.
    LaunchedEffect(packageName) { viewModel.loadDetail(packageName) }
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
                modifier = Modifier.weight(DETAIL_IDENTITY_WEIGHT).fillMaxHeight(),
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
        TvUninstallConfirm(
            app = app,
            onDismiss = { showUninstallConfirm = false },
            onConfirm = {
                showUninstallConfirm = false
                onUninstall(app.packageName)
            },
        )
    }
}

/** Uninstall confirmation dialog for the detail screen. */
@Composable
private fun TvUninstallConfirm(
    app: UnifiedApp,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    TvDialog(
        title = stringResource(R.string.uninstall_3, app.name),
        body = stringResource(
            R.string.this_will_uninstall_you_can_reinstall_la, app.packageName
        ),
        onDismissRequest = onDismiss,
    ) {
        TvButton(
            text = stringResource(UiR.string.cancel),
            onClick = onDismiss,
        )
        TvButton(
            text = stringResource(R.string.uninstall),
            onClick = onConfirm,
            primary = true,
        )
    }
}

/**
 * Left pane: icon, name, developer, source badge, primary Install/Update/Open
 * plus the Open/Uninstall secondary exactly like the phone, then the stats row.
 */
