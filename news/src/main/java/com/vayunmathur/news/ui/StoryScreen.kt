package com.vayunmathur.news.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconBookmark
import com.vayunmathur.library.ui.IconBookmarkBorder
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.news.R
import com.vayunmathur.news.domain.SourceCatalog
import com.vayunmathur.news.platform.NewsViewModel

/**
 * Story page: how one event developed, earliest → latest. Each
 * article row (source, time, headline) opens the existing Reader
 * (which fetches its body on-device). Single-article stories show
 * just that article — no timeline/count/summary chrome.
 * The server one-liner, when present, is shown as a labelled
 * summary; no server-extracted article text is ever displayed.
 */
@Composable
fun StoryScreen(
    viewModel: NewsViewModel,
    storyId: String,
    onOpenArticle: (String) -> Unit,
    onBack: () -> Unit,
) {
    val allStories by viewModel.allStories.collectAsState()
    val cachedByUrl by viewModel.cachedByUrl.collectAsState()
    val story = allStories.firstOrNull { it.id == storyId }
    AppScaffold(title = "", onNavigateBack = onBack) { padding ->
        if (story == null) {
            Text(
                stringResource(R.string.story_not_found),
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            return@AppScaffold
        }
        val single = story.articles.size == 1
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(story.title, style = MaterialTheme.typography.headlineSmall)
                    if (!single) {
                        val range = buildString {
                            append(story.sourceNames.joinToString(", "))
                            val first = relativeTime(story.firstSeenMillis)
                            val latest = relativeTime(story.latestMillis)
                            if (first.isNotEmpty()) append(" · from $first")
                            if (latest.isNotEmpty() && latest != first) append(" · updated $latest")
                        }
                        Text(
                            range,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        if (story.oneLiner != null) {
                            Text(
                                stringResource(R.string.story_summary_label),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            Text(story.oneLiner!!, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            stringResource(R.string.story_timeline_label),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                        )
                    }
                }
            }
            items(story.articles, key = { it.url }) { article ->
                val cached = cachedByUrl[article.url]
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onOpenArticle(article.url) }.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${SourceCatalog.byId(article.sourceId)?.name ?: article.sourceId} · ${relativeTime(article.publishedAtMillis)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(article.title, style = MaterialTheme.typography.titleMedium)
                    }
                    if (cached != null) {
                        IconButton(onClick = { viewModel.toggleBookmark(cached) }) {
                            if (cached.bookmarked) IconBookmark() else IconBookmarkBorder()
                        }
                    }
                }
            }
        }
    }
}
