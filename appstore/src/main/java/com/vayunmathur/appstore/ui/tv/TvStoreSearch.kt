package com.vayunmathur.appstore.ui.tv
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.platform.SourceFilter
import com.vayunmathur.appstore.platform.TvSearchUiState
import com.vayunmathur.appstore.platform.TvStoreViewModel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.ui.labelRes
import com.vayunmathur.library.tv.TvChip
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvTextField
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconSearch

/**
 * The TV search hub: a D-pad text field, source-filter chips, and the
 * local-then-remote merged results as a 3-column grid of full-information cards.
 *
 * One scrolling grid owns everything — the field and chips are full-width grid
 * items — so DOWN from the field lands on the chips and DOWN again on results.
 * The keyboard opens only on an explicit CENTER press on the field
 * ([TvTextField]); selecting the Search rail destination does not start editing.
 * Results arrive in two waves — the on-disk catalogues answer first, the network
 * merges in — and a Play refusal (e.g. 429) is said above the results, never a
 * silent gap. Mirrors the phone's Search page.
 */
@Composable
fun TvStoreSearch(
    viewModel: TvStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    modifier: Modifier = Modifier,
    /** True when the user navigated here explicitly: start with the field editing. */
    startEditing: Boolean = false,
) {
    val state by viewModel.search.collectAsState()
    TvStoreSearchScreen(
        state = state,
        startEditing = startEditing,
        onQueryChange = viewModel::setSearch,
        onFilterChange = viewModel::setSearchFilter,
        onAppClick = onAppClick,
        modifier = modifier,
    )
}

@Composable
internal fun TvStoreSearchScreen(
    state: TvSearchUiState,
    modifier: Modifier = Modifier,
    startEditing: Boolean = false,
    onQueryChange: (String) -> Unit = {},
    onFilterChange: (SourceFilter) -> Unit = {},
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    val fieldFocus = remember { FocusRequester() }
    // Play's half refused (429): say so above the results rather than silently
    // showing a list that is missing half its sources.
    val playError = state.playError.takeIf { it.isNotBlank() }
    // Unkeyed: runs once. Keying on the query would yank focus back to the
    // field on every keystroke.
    TvInitialFocus(fieldFocus)
    TvGrid(columns = 3, modifier = modifier.fillMaxSize()) {
        tvFullWidth(key = "header") {
            TvScreenHeader(title = stringResource(R.string.nav_search))
        }
        tvFullWidth(key = "field") {
            TvTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.search_placeholder),
                leading = { IconSearch() },
                imeAction = ImeAction.Search,
                focusRequester = fieldFocus,
                startEditing = startEditing && state.query.isBlank(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        tvFullWidth(key = "filters") {
            TvStoreSearchFilters(filter = state.filter, onFilterChange = onFilterChange)
        }
        if (playError != null) {
            tvFullWidth(key = "play-error") {
                Text(
                    text = playError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        tvSearchResults(state = state, onAppClick = onAppClick)
    }
}

/** Source-filter chips as a focusable D-pad row in one full-width grid item. */
@Composable
private fun TvStoreSearchFilters(
    filter: SourceFilter,
    onFilterChange: (SourceFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (entry in SourceFilter.entries) {
            TvChip(
                label = stringResource(entry.labelRes()),
                selected = filter == entry,
                onClick = { onFilterChange(entry) },
            )
        }
    }
}

/** Prompt, loading, empty and result states inside the search grid. */
private fun LazyGridScope.tvSearchResults(
    state: TvSearchUiState,
    onAppClick: (UnifiedApp) -> Unit,
) {
    when {
        state.query.isBlank() -> tvFullWidth(key = "prompt") {
            TvEmptyState(
                title = stringResource(R.string.search_prompt_title),
                body = stringResource(R.string.tv_search_prompt_tv),
                icon = { IconSearch() },
            )
        }
        state.results.isEmpty() && (state.isSearching || !state.hasSearched) ->
            tvFullWidth(key = "loading") {
                TvLoading(label = stringResource(R.string.sync_in_progress))
            }
        state.results.isEmpty() -> tvFullWidth(key = "no-results") {
            TvEmptyState(
                title = stringResource(R.string.search_no_results_title, state.query),
                body = stringResource(R.string.search_no_results_message),
                icon = { IconPackage() },
            )
        }
        else -> items(state.results, key = { it.packageName }) { app ->
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
