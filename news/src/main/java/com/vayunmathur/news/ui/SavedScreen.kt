package com.vayunmathur.news.ui

import androidx.compose.foundation.layout.fillMaxSize
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
import com.vayunmathur.library.ui.Text
import com.vayunmathur.news.R
import com.vayunmathur.news.platform.NewsViewModel

/** Bookmarked articles; their bodies are cached, so they open offline. */
@Composable
fun SavedScreen(
    viewModel: NewsViewModel,
    onOpenArticle: (String) -> Unit,
    onBack: () -> Unit,
) {
    val saved by viewModel.bookmarked.collectAsState()
    AppScaffold(
        title = stringResource(R.string.saved_title),
        onNavigateBack = onBack,
    ) { padding ->
        if (saved.isEmpty()) {
            Text(
                stringResource(R.string.saved_empty),
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(saved, key = { it.url }) { article ->
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
