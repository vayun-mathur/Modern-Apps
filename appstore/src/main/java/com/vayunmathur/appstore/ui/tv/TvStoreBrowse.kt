package com.vayunmathur.appstore.ui.tv
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.platform.TvBrowseUiState
import com.vayunmathur.appstore.platform.TvStoreViewModel
import com.vayunmathur.appstore.platform.loadBrowse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.ui.IconPackage

/**
 * One home section's full catalogue as a grid of full-information cards.
 * Mirrors the phone's Browse page: the home rows are capped slices, this lists
 * literally every app behind the section, A–Z, in the same card search uses.
 */
@Composable
fun TvStoreBrowse(
    viewModel: TvStoreViewModel,
    sectionId: String,
    onAppClick: (UnifiedApp) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The Browse route's argument, reloaded whenever it changes so a
    // restored entry shows its own section.
    LaunchedEffect(sectionId) { viewModel.loadBrowse(sectionId) }
    val state by viewModel.browse.collectAsState()
    BackHandler(onBack = onBack)
    TvStoreBrowseScreen(
        state = state,
        onAppClick = onAppClick,
        modifier = modifier,
    )
}

@Composable
internal fun TvStoreBrowseScreen(
    state: TvBrowseUiState,
    modifier: Modifier = Modifier,
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    when {
        state.isLoading && state.apps.isEmpty() ->
            TvLoading(label = stringResource(R.string.sync_in_progress))
        state.apps.isEmpty() -> TvEmptyState(
            title = stringResource(R.string.browse_empty_title),
            body = stringResource(R.string.browse_empty_message),
            icon = { IconPackage() },
        )
        else -> TvGrid(columns = 3, modifier = modifier.fillMaxSize()) {
            tvFullWidth(key = "header") {
                TvScreenHeader(title = state.title)
            }
            items(state.apps, key = { it.packageName }) { app ->
                TvStoreAppCard(
                    app = app,
                    onClick = { onAppClick(app) },
                    installedIcon = state.installedIcons[app.packageName],
                    isInstalled = app.packageName in state.installedPackages,
                    stage = state.stages[app.packageName],
                )
            }
        }
    }
}
