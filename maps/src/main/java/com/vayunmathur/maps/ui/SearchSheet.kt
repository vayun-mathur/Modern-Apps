package com.vayunmathur.maps.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextFieldDefaults
import com.vayunmathur.library.util.round
import com.vayunmathur.maps.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.maps.util.SearchActions
import com.vayunmathur.maps.util.SearchPhase
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SearchUiState

/**
 * A fixed height for the three states that have nothing to list.
 *
 * The results and recents lists deliberately fill whatever the sheet offers, so the user can drag
 * the sheet up and get more rows. "Searching", "no results" and "type something" have no rows to
 * give, so filling would let the sheet be dragged to full height over an empty surface.
 */
private val StatusBlockHeight = 120.dp

/** Widest the trailing locality text may grow before it wraps (two lines, then ellipsis). */
private val LocalityMaxWidth = 150.dp

/**
 * Search, as a sheet over the live map.
 *
 * Everything above this sheet is the real map — still drawing, still pannable, still showing the
 * result pins as they arrive — which is the reason search is a sheet at all rather than a page.
 * The sheet owns the query: the back arrow leaves search, the X clears the text without leaving.
 *
 * Stateless, like the page it replaces, so the store-listing preview can render it from literal
 * state. [autoFocus] is what the preview turns off: there is no keyboard to raise in Layoutlib.
 */
@Composable
fun SearchSheet(
    state: SearchUiState,
    actions: SearchActions,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        SearchField(state.query, actions, autoFocus)
        CategoryChips(
            onCategory = { actions.setQuery(it.query) },
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
            contentPadding = PaddingValues(horizontal = Spacing.lg),
        )
        when (state.phase) {
            SearchPhase.Searching -> LoadingState(Modifier.height(StatusBlockHeight))
            SearchPhase.Empty -> EmptyState(
                title = stringResource(R.string.no_results_found),
                modifier = Modifier.height(StatusBlockHeight),
            )
            SearchPhase.Results -> ResultsList(state.results, actions)
            SearchPhase.Recents -> RecentsList(state.recents, actions)
            SearchPhase.Idle -> Box(
                Modifier.fillMaxWidth().height(StatusBlockHeight),
                Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.type_to_search),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The pill: back arrow, the editable query, and either the clear button or the two shortcuts.
 *
 * The clear button replaces the shortcuts rather than joining them, because an X next to a mic
 * next to a contact picker is three glyphs competing for the same corner and the X is the one the
 * user is reaching for while there is text to clear.
 */
@Composable
private fun SearchField(query: String, actions: SearchActions, autoFocus: Boolean) {
    val focusRequester = remember { FocusRequester() }
    // The sheet exists to be typed into, so it opens with the caret already in the field.
    LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ actions.back() }) { IconBack() }
            TextField(
                value = query,
                onValueChange = { actions.setQuery(it) },
                placeholder = { Text(stringResource(R.string.search_nearby)) },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                // The card is the pill; the field inside it must contribute neither a container
                // of its own nor the filled field's underline, or the shape reads as two.
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                singleLine = true,
            )
            if (query.isEmpty()) {
                ContactAddressButton(onAddress = { actions.pickContactAddress(it) })
                VoiceSearchButton(onResult = { actions.setQuery(it) })
            } else {
                IconButton({ actions.setQuery("") }) { IconClose() }
            }
        }
    }
}

@Composable
private fun ResultsList(results: List<SearchResult>, actions: SearchActions) {
    LazyColumn(Modifier.fillMaxWidth()) {
        items(results, key = { it.id }) { result ->
            ListItem(
                content = { Text(result.title) },
                supportingContent = {
                    Text(
                        result.subtitle
                            ?: stringResource(R.string.coordinates, result.lat.round(4), result.lon.round(4))
                    )
                },
                // Where the place is, so two same-named rows (every "Starbucks") can be told
                // apart at a glance. Capped in width and lines so a long "City, State,
                // Country" never squeezes the name itself.
                trailingContent = result.locality?.let { locality ->
                    {
                        Text(
                            locality,
                            modifier = Modifier.widthIn(max = LocalityMaxWidth),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                modifier = Modifier.clickable { actions.selectResult(result) },
            )
            HorizontalDivider(Modifier.padding(horizontal = Spacing.lg))
        }
    }
}

@Composable
private fun RecentsList(recents: List<String>, actions: SearchActions) {
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.recent_searches),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(UiR.string.clear),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { actions.clearRecents() },
                )
            }
        }
        items(recents, key = { it }) { recent ->
            ListItem(
                content = { Text(recent) },
                leadingContent = { IconHistory() },
                modifier = Modifier.clickable { actions.setQuery(recent) },
            )
            HorizontalDivider(Modifier.padding(horizontal = Spacing.lg))
        }
    }
}
