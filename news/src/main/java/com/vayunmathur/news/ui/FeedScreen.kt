package com.vayunmathur.news.ui

import androidx.compose.foundation.layout.Column
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
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconBookmark
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.Text
import com.vayunmathur.news.R
import com.vayunmathur.news.domain.SourceCatalog
import com.vayunmathur.news.platform.FILTER_ALL
import com.vayunmathur.news.platform.NewsViewModel

/** Merged newest-first feed with per-source filter chips and pull-to-refresh. */
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
    val filter by viewModel.sourceFilter.collectAsState()
    val disabled by viewModel.disabledSourceIds.collectAsState()
    AppScaffold(
        title = stringResource(R.string.feed_title),
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        actions = {
            IconButton(onClick = onOpenSaved) { IconBookmark() }
            IconButton(onClick = onOpenSources) { IconSettings() }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyRow(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                item {
                    FilterChip(
                        selected = filter == FILTER_ALL,
                        onClick = { viewModel.setSourceFilter(FILTER_ALL) },
                        label = { Text(stringResource(R.string.feed_all_sources)) },
                    )
                }
                items(SourceCatalog.sources.filter { it.id !in disabled }) { source ->
                    FilterChip(
                        selected = filter == source.id,
                        onClick = { viewModel.setSourceFilter(source.id) },
                        label = { Text(source.name) },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (refreshFailed) {
                Text(
                    stringResource(R.string.feed_refresh_failed),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (articles.isEmpty() && !isRefreshing) {
                Text(
                    stringResource(R.string.feed_empty),
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(articles, key = { it.url }) { article ->
                    ArticleRow(
                        article = article,
                        onClick = { onOpenArticle(article.url) },
                        onToggleBookmark = { viewModel.toggleBookmark(article) },
                    )
                }
            }
        }
    }
}
