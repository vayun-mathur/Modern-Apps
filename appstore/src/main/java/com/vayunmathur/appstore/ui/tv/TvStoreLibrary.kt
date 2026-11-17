package com.vayunmathur.appstore.ui.tv
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.platform.SourceFilter
import com.vayunmathur.appstore.platform.TvLibraryUiState
import com.vayunmathur.appstore.platform.TvStoreViewModel
import com.vayunmathur.appstore.platform.refreshInstalled
import com.vayunmathur.appstore.platform.setLibraryFilter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.ui.labelRes
import com.vayunmathur.library.tv.TvChip
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.ui.IconPackage

/**
 * Every installed app the store can attribute to a source, as a grid of
 * full-information cards with the phone's source filter chips.
 *
 * Uninstall lives only on an app's own page: a delete action next to every card
 * in a long grid is too easy to land on with a D-pad. Appearing on this
 * destination refreshes the device's installed set, so an install that landed
 * while browsing shows up.
 */
@Composable
fun TvStoreLibrary(
    viewModel: TvStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.library.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshInstalled() }
    TvStoreLibraryScreen(
        state = state,
        onFilterChange = viewModel::setLibraryFilter,
        onAppClick = onAppClick,
        modifier = modifier,
    )
}

@Composable
internal fun TvStoreLibraryScreen(
    state: TvLibraryUiState,
    modifier: Modifier = Modifier,
    onFilterChange: (SourceFilter) -> Unit = {},
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    if (state.apps.isEmpty()) {
        TvEmptyState(
            title = stringResource(R.string.library_empty_title),
            body = stringResource(R.string.library_empty_message),
            icon = { IconPackage() },
        )
        return
    }
    TvGrid(columns = 3, modifier = modifier.fillMaxSize()) {
        tvFullWidth(key = "header") {
            TvScreenHeader(title = stringResource(R.string.nav_library))
        }
        tvFullWidth(key = "filters") {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (entry in SourceFilter.entries) {
                    val count = state.counts[entry] ?: 0
                    TvChip(
                        label = stringResource(
                            R.string.filter_with_count,
                            stringResource(entry.labelRes()),
                            count,
                        ),
                        selected = state.filter == entry,
                        onClick = { onFilterChange(entry) },
                    )
                }
            }
        }
        items(state.apps, key = { it.packageName }) { app ->
            TvStoreAppCard(
                app = app,
                onClick = { onAppClick(app) },
                installedIcon = state.installedIcons[app.packageName],
                isInstalled = true,
                stage = null,
                versionLabel = app.versionName,
            )
        }
    }
}
