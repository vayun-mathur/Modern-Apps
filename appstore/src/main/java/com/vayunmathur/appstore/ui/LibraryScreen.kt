package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R

import com.vayunmathur.appstore.platform.LibraryActions
import com.vayunmathur.appstore.platform.LibraryUiState
import com.vayunmathur.appstore.platform.SourceFilter
import com.vayunmathur.appstore.platform.setLibraryFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text

/**
 * Every app installed on the device, grouped by which source offers it.
 *
 * Uninstall lives only on an app's own page — a delete button next to every row in a long
 * list is too easy to hit by accident.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    // Whole-content loading: the list IS the content and the chips only filter a
    // loaded list, so there is no meaningful filter state before the first sweep.
    // isRefreshing is gated on hasLoaded, which never resets, so filtering a loaded
    // library (even down to empty) keeps the chips visible above the empty state.
    // While the first sweep runs the scaffold shows its top indicator over an
    // empty placeholder: neither chips nor the empty state may show before load.
    AppScaffold(
        topBar = {},
        isRefreshing = !state.hasLoaded && state.apps.isEmpty(),
    ) { padding ->
        if (!state.hasLoaded) {
            Box(Modifier.fillMaxSize().padding(padding))
        } else Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SourceFilter.entries.toList(), key = { it.name }) { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { actions.setLibraryFilter(filter) },
                        label = {
                            Text(
                                stringResource(
                                    R.string.filter_with_count,
                                    stringResource(filter.labelRes()),
                                    state.counts[filter] ?: 0,
                                )
                            )
                        },
                    )
                }
            }

            if (state.apps.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.library_empty_title),
                    message = stringResource(R.string.library_empty_message),
                    icon = { IconPackage() },
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
                ) {
                    items(state.apps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            isInstalled = true,
                            installedIcon = state.installedIcons[app.packageName],
                            onClick = { onAppClick(app) },
                            sharedKey = "appstore-app-${app.packageName}",
                        )
                    }
                }
            }
        }
    }
}
