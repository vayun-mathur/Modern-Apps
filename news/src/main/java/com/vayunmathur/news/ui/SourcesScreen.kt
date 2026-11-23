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
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.news.R
import com.vayunmathur.news.domain.SourceCatalog
import com.vayunmathur.news.platform.NewsViewModel

/** Built-in source catalog with per-source enable switches. */
@Composable
fun SourcesScreen(viewModel: NewsViewModel, onBack: () -> Unit) {
    val disabled by viewModel.disabledSourceIds.collectAsState()
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
                    ListItem(
                        headlineContent = { Text(source.name) },
                        supportingContent = {
                            Text(source.badge ?: source.feedUrl)
                        },
                        trailingContent = {
                            Switch(
                                checked = source.id !in disabled,
                                onCheckedChange = { viewModel.setSourceEnabled(source.id, it) },
                            )
                        },
                    )
                }
            }
        }
    }
}
