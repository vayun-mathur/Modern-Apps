package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R

import com.vayunmathur.appstore.platform.SearchActions
import com.vayunmathur.appstore.platform.SearchUiState
import com.vayunmathur.appstore.platform.SourceFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.friendlyErrorMessage
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text

/**
 * Search across every source at once.
 *
 * Results arrive in two waves: the on-disk F-Droid and Modern Apps catalogues answer
 * instantly, and Play's reply merges in a moment later. That is why the progress line
 * sits above a list that already has content in it rather than replacing it — the first
 * wave is a real answer, not a placeholder.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    actions: SearchActions,
    onAppClick: (UnifiedApp) -> Unit = {},
    autoFocus: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) runCatching { focusRequester.requestFocus() }
    }

    AppScaffold(topBar = {}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            CommonSearchBar(
                value = state.query,
                onValueChange = actions::setSearch,
                placeholder = stringResource(R.string.search_placeholder),
                padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.focusRequester(focusRequester),
            )

            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SourceFilter.entries.toList(), key = { it.name }) { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { actions.setSearchFilter(filter) },
                        label = { Text(stringResource(filter.labelRes())) },
                    )
                }
            }

            if (state.isSearching) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }

            // Play's half refused (429, issue #797): say so above the results rather than
            // silently showing a list that is missing half its sources.
            val playError = state.playError.takeIf { it.isNotBlank() }
            if (playError != null && state.results.isEmpty()) {
                ErrorState(
                    title = stringResource(R.string.search_failed_title),
                    message = friendlyErrorMessage(playError),
                    retryLabel = stringResource(com.vayunmathur.library.ui.R.string.retry),
                    onRetry = { actions.setSearch(state.query) },
                )
            } else if (playError != null) {
                // Partial failure: local results are on screen, so keep the
                // notice as a banner above them (friendly text, not raw).
                Text(
                    friendlyErrorMessage(playError),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when {
                state.query.isBlank() -> EmptyState(
                    title = stringResource(R.string.search_prompt_title),
                    message = stringResource(R.string.search_prompt_message),
                    icon = { IconSearch() },
                )

                state.results.isEmpty() && state.isSearching -> Loading()

                state.results.isEmpty() && state.hasSearched -> EmptyState(
                    title = stringResource(R.string.search_no_results_title, state.query),
                    message = stringResource(R.string.search_no_results_message),
                    icon = { IconSearch() },
                )

                state.results.isEmpty() -> Loading()

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                ) {
                    items(
                        state.results,
                        key = { it.packageName + it.source.name },
                    ) { app ->
                        AppRow(
                            app = app,
                            isInstalled = app.packageName in state.installedPackages,
                            stage = state.stages[app.packageName],
                            installedIcon = state.installedIcons[app.packageName],
                            onClick = { onAppClick(app) },
                            // Unambiguous despite the source in the item key: results are
                            // deduplicated by package before they get here.
                            sharedKey = "appstore-app-${app.packageName}",
                        )
                    }
                }
            }
        }
    }
}
