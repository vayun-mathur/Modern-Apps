package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R

import com.vayunmathur.appstore.platform.AppDetailActions
import com.vayunmathur.appstore.platform.AppDetailUiState
import com.vayunmathur.appstore.platform.loadDetail
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text

/**
 * One app's page: what it is, what it looks like, and what installing it would mean.
 *
 * The previous version led with a wall of label/value rows, which is a debug dump rather
 * than a listing. The order here is the order the questions actually get asked —
 * screenshots, the install button, the description, then the facts, then provenance.
 *
 * Stateless so it can be rendered from a `@Preview` — see `src/screenshotTest`, which is
 * where the store listing images come from.
 */
@Composable
fun AppDetailScreen(
    state: AppDetailUiState,
    actions: AppDetailActions,
    onBack: () -> Unit = {},
    onOpenTrust: () -> Unit = {},
    /** Opens the full-screen viewer at this index into `app.screenshots` (#918). */
    onOpenScreenshot: (Int) -> Unit = {},
) {
    val app = state.app ?: return
    var showUninstallConfirm by remember { mutableStateOf(false) }

    AppScaffold(
        title = { Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onNavigateBack = onBack,
        alignment = AppBarAlignment.Center,
        actions = {
            IconButton(onClick = { actions.shareApp(app) }) { IconShare() }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppDetailHeader(state)
            AppInstallActions(state, actions, onUninstall = { showUninstallConfirm = true })
            if (app.screenshots.isNotEmpty()) {
                AppScreenshotStrip(app.screenshots, app.name, onOpen = onOpenScreenshot)
            }
            AppDetailFacts(app)
            AppDetailDescription(app)
            AppDetailWhatsNew(app)
            AppDetailChips(app)
            AppTrustCard(app.source, state.verification, onOpenTrust)
            AppDetailLinks(app, actions)
            AppDetailDetails(app)
        }
    }

    if (showUninstallConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.uninstall_3, app.name),
            message = stringResource(R.string.this_will_uninstall_you_can_reinstall_la, app.packageName),
            confirmLabel = stringResource(R.string.uninstall),
            dismissLabel = stringResource(UiR.string.cancel),
            onConfirm = { actions.uninstallApp(app.packageName) },
            onDismiss = { showUninstallConfirm = false },
            destructive = true,
        )
    }
}
