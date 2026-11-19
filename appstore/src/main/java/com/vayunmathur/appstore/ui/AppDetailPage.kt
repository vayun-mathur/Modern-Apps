package com.vayunmathur.appstore.ui
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.appstore.platform.loadDetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.vayunmathur.appstore.platform.AppStoreViewModel

/**
 * Binds [AppStoreViewModel] to the stateless [AppDetailScreen] for
 * [packageName] — the `Route.Detail` argument, reloaded whenever it changes
 * so a restored entry shows its own app.
 */
@Composable
fun AppDetailPage(
    viewModel: AppStoreViewModel,
    packageName: String,
    onBack: () -> Unit,
    onOpenTrust: () -> Unit = {},
    onOpenScreenshot: (Int) -> Unit = {},
) {
    LaunchedEffect(packageName) { viewModel.loadDetail(packageName) }
    val state by viewModel.detail.collectAsState()
    AppDetailScreen(
        state = state,
        actions = viewModel,
        onBack = onBack,
        onOpenTrust = onOpenTrust,
        onOpenScreenshot = onOpenScreenshot,
    )
}

