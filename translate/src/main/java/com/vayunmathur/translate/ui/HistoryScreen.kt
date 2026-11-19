package com.vayunmathur.translate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.translate.R
import com.vayunmathur.translate.data.TranslationRecord
import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.platform.HistoryActions
import com.vayunmathur.translate.platform.HistoryUiState

/**
 * Saved translations, favorites first then newest — with a star to pin, a delete
 * per row, and a clear-all for everything unstarred. Tapping a row loads it back
 * into the text screen.
 *
 * Stateless like [TextTranslateScreen]: the binder page collects the flows and the
 * previews render this directly.
 */
@Composable
fun HistoryScreen(state: HistoryUiState, actions: HistoryActions) {
    AppScaffold(
        title = stringResource(R.string.history),
        onNavigateBack = actions::goBack,
        actions = {
            if (state.records.any { !it.isFavorite }) {
                TextButton(onClick = actions::clearHistory) {
                    Text(stringResource(R.string.clear_history))
                }
            }
        },
    ) { padding ->
        if (state.records.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.history_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@AppScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.records, key = { it.id }) { record ->
                HistoryRow(
                    record = record,
                    onToggleFavorite = { actions.toggleFavorite(record.id, !record.isFavorite) },
                    onDelete = { actions.delete(record) },
                    onReuse = { actions.reuse(record) },
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    record: TranslationRecord,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onReuse: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onReuse) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = languagePair(record.sourceLang, record.targetLang),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleFavorite) {
                    IconFavorite(
                        tint = if (record.isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onDelete) {
                    IconDelete()
                }
            }
            Text(
                text = record.sourceText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = record.translatedText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun languagePair(source: String, target: String): String {
    val from = Languages.displayName(source)
    val to = Languages.displayName(target)
    return stringResource(R.string.history_language_pair, from, to)
}
