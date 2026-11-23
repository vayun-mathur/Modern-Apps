package com.vayunmathur.news.ui

import androidx.compose.foundation.layout.Column
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
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.news.R
import com.vayunmathur.news.domain.SourceCatalog
import com.vayunmathur.news.platform.NewsViewModel

/**
 * Read-only source directory: every source is always in the corpus
 * (categories and search are the filters), so there is nothing to
 * toggle here — just name, language, metered badge and how many of its
 * articles are currently cached.
 */
@Composable
fun SourcesScreen(viewModel: NewsViewModel, onBack: () -> Unit) {
    val counts by viewModel.sourceArticleCounts.collectAsState()
    AppScaffold(
        title = stringResource(R.string.sources_title),
        onNavigateBack = onBack,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.sources_note),
                modifier = Modifier.padding(16.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(SourceCatalog.sources, key = { it.id }) { source ->
                    val count = counts[source.id] ?: 0
                    ListItem(
                        headlineContent = { Text(source.name) },
                        supportingContent = {
                            Column {
                                val detail = buildString {
                                    append(source.language)
                                    if (count > 0) append(" · $count cached")
                                }
                                Text(detail)
                                if (source.badge != null) Text(source.badge)
                            }
                        },
                        trailingContent = {
                            if (source.badge?.startsWith("Metered") == true) {
                                Text(stringResource(R.string.sources_metered))
                            }
                        },
                    )
                }
            }
        }
    }
}
