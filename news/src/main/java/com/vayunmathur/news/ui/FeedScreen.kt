package com.vayunmathur.news.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ScaffoldSearch
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconBookmark
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.Text
import com.vayunmathur.news.R
import com.vayunmathur.news.domain.FeedSort
import com.vayunmathur.news.domain.SourceCatalog
import com.vayunmathur.news.platform.NewsViewModel

/**
 * Merged feed with category chips, a Top stories / Latest sort selector
 * and pull-to-refresh. Every source is always in the corpus; chips are
 * the categories of all sources (plus the current selection, so it
 * stays tappable). Feed controls hide while searching — search keeps
 * its relevance order. Selecting a category also narrows search
 * results (see NewsViewModel.searchResults).
 */
@Composable
fun FeedScreen(
    viewModel: NewsViewModel,
    onOpenArticle: (String) -> Unit,
    onOpenSources: () -> Unit,
    onOpenSaved: () -> Unit,
) {
    val articles by viewModel.articles.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val refreshFailed by viewModel.refreshFailed.collectAsState()
    val categoryFilter by viewModel.categoryFilter.collectAsState()
    val feedSort by viewModel.feedSort.collectAsState()
    val clusterCounts by viewModel.clusterCounts.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val searching = searchQuery.isNotBlank()
    val shownArticles = if (searching) searchResults else articles
    AppScaffold(
        // The search field IS the top app bar (no "News" title): declared
        // through the scaffold's search parameter, which renders the field
        // in the bar's title slot — the ListPage / notes / contacts placement.
        title = "",
        search = ScaffoldSearch(
            query = searchQuery,
            onQueryChange = viewModel::setSearchQuery,
            placeholder = stringResource(R.string.feed_search_placeholder),
        ),
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        actions = {
            IconButton(onClick = onOpenSaved) { IconBookmark() }
            IconButton(onClick = onOpenSources) { IconSettings() }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!searching) {
            // Sort selector: persisted Top stories (ranked) / Latest.
            LazyRow(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                item {
                    FilterChip(
                        selected = feedSort == FeedSort.TOP,
                        onClick = { viewModel.setFeedSort(FeedSort.TOP) },
                        label = { Text(stringResource(R.string.feed_sort_top)) },
                    )
                }
                item {
                    FilterChip(
                        selected = feedSort == FeedSort.LATEST,
                        onClick = { viewModel.setFeedSort(FeedSort.LATEST) },
                        label = { Text(stringResource(R.string.feed_sort_latest)) },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            // Category chips (single-select, "All" = no category filter).
            val categories = (SourceCatalog.categoriesAmong(SourceCatalog.sources) +
                listOfNotNull(categoryFilter)).distinct()
                .sortedBy { it.ordinal }
            LazyRow(modifier = Modifier.padding(horizontal = 12.dp)) {
                item {
                    FilterChip(
                        selected = categoryFilter == null,
                        onClick = { viewModel.setCategoryFilter(null) },
                        label = { Text(stringResource(R.string.feed_all_categories)) },
                    )
                }
                items(categories) { category ->
                    FilterChip(
                        selected = categoryFilter == category,
                        onClick = { viewModel.setCategoryFilter(category) },
                        label = { Text(category.label) },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            }
            if (refreshFailed && !searching) {
                Text(
                    stringResource(R.string.feed_refresh_failed),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (shownArticles.isEmpty() && !isRefreshing) {
                Text(
                    stringResource(if (searching) R.string.feed_search_empty else R.string.feed_empty),
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shownArticles, key = { it.url }) { article ->
                    ArticleRow(
                        article = article,
                        onClick = { onOpenArticle(article.url) },
                        onToggleBookmark = { viewModel.toggleBookmark(article) },
                        alsoFromSources = if (searching) 0 else clusterCounts[article.url] ?: 0,
                    )
                }
            }
        }
    }
}
