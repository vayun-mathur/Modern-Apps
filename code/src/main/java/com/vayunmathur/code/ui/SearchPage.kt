package com.vayunmathur.code.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.code.Route
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.delay

/**
 * Project-wide search: a query field with case/regex toggles over a list of matching lines,
 * grouped by file. Tapping a result opens the file and jumps to the line, then returns to the
 * editor. The search itself runs in the ViewModel off the main thread and is debounced here.
 */
@Composable
fun SearchPage(viewModel: EditorViewModel, backStack: NavBackStack<Route>) {
    var query by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var useRegex by remember { mutableStateOf(false) }
    val state = viewModel.uiState

    LaunchedEffect(query, caseSensitive, useRegex) {
        if (query.isBlank()) return@LaunchedEffect
        delay(250)
        viewModel.actions.searchProject(query, caseSensitive, useRegex)
    }

    AppScaffold(title = stringResource(UiR.string.search), backStack = backStack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.search_in_project)) },
                leadingIcon = { IconSearch() },
                singleLine = true,
            )
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleChip(
                    label = stringResource(R.string.match_case),
                    selected = caseSensitive,
                    onClick = { caseSensitive = !caseSensitive },
                )
                ToggleChip(
                    label = stringResource(R.string.use_regex),
                    selected = useRegex,
                    onClick = { useRegex = !useRegex },
                )
            }

            when {
                state.isSearching -> Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { Text("Loading\u2026") }

                query.isNotBlank() && state.searchResults.isEmpty() -> Text(
                    stringResource(R.string.no_results),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val groups = remember(state.searchResults) {
                state.searchResults.groupBy { it.name to it.path }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (key, results) ->
                    item(key = "header-${key.second}") {
                        Text(
                            text = key.first,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    items(results) { result ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.actions.openSearchResult(result)
                                    backStack.pop()
                                }
                                .padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = result.line.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = result.preview,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A small text toggle that colours itself when active; shared look with the editor find bar. */
@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
