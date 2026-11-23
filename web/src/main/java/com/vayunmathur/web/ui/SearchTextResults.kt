package com.vayunmathur.web.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.web.R
import com.vayunmathur.web.data.WebSearchTextItem
import com.vayunmathur.web.platform.BrowserUtils

/**
 * Text-result list for [SearchResultsPage]: title + host + snippet rows with
 * a trailing "more results" affordance that appends the next page.
 */
@Composable
fun SearchTextResults(
    items: List<WebSearchTextItem>,
    loadingMore: Boolean,
    onOpenResult: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.search_no_results),
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items, key = { it.url + it.title }) { item ->
            ListItem(
                headlineContent = {
                    Text(item.title.ifBlank { item.url }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Column {
                        Text(
                            BrowserUtils.prettyUrl(item.url),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (item.snippet.isNotBlank()) {
                            Text(
                                item.snippet,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                modifier = Modifier.clickable { onOpenResult(item.url) },
            )
        }
        if (loadingMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Loading…")
                }
            }
        } else {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = onLoadMore) {
                        Text(stringResource(R.string.search_more_results))
                    }
                }
            }
        }
    }
}
