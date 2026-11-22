package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R

import com.vayunmathur.appstore.platform.BrowseUiState
import com.vayunmathur.appstore.platform.loadBrowse
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconSearch

/**
 * One home section's full catalogue as a vertical list.
 *
 * A home carousel shows a capped slice — the 20 newest builds, the first 20 tiles.
 * This page lists literally every app behind that section, A–Z, in the same
 * [AppRow] the search page uses.
 *
 * Stateless so it can be rendered from a `@Preview` — see `src/screenshotTest`,
 * which is where the store listing images come from.
 */
@Composable
fun BrowseScreen(
    state: BrowseUiState,
    onAppClick: (UnifiedApp) -> Unit = {},
    onBack: () -> Unit = {},
) {
    AppScaffold(
        title = state.title,
        onNavigateBack = onBack,
        alignment = AppBarAlignment.Start,
        isLoading = state.isLoading && state.apps.isEmpty(),
    ) { padding ->
        when {
            state.apps.isEmpty() -> EmptyState(
                title = stringResource(R.string.browse_empty_title),
                message = stringResource(R.string.browse_empty_message),
                icon = { IconSearch() },
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                // Morph ownership mirrors the home screen: the browse page and home are
                // never composed at the same time (pushed route), so this list owns the
                // morph for every app while it is on screen.
                items(
                    state.apps,
                    key = { it.packageName },
                ) { app ->
                    AppRow(
                        app = app,
                        isInstalled = app.packageName in state.installedPackages,
                        stage = state.stages[app.packageName],
                        installedIcon = state.installedIcons[app.packageName],
                        onClick = { onAppClick(app) },
                        sharedKey = "appstore-app-${app.packageName}",
                    )
                }
            }
        }
    }
}
