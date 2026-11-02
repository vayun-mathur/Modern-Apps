package com.vayunmathur.securemail.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.securemail.data.OutboxMessage

/** Outbox: queued sends awaiting reconnect, with manual retry. No app bar (it is a tab). */
@Composable
fun OutboxScreen(
    onRetry: () -> Unit,
    onCompose: () -> Unit = {},
    pending: List<OutboxMessage> = emptyList(),
) {
    AppScaffold(
        topBar = {},
        floatingActionButton = { FloatingActionButton(onClick = onCompose) { IconAdd() } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (pending.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Outbox is empty.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Retry all") }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(pending, key = { it.rowId }) { entry ->
                        ListItem(
                            supportingContent = {
                                Column {
                                    Text(
                                        "To: ${entry.toJson}",
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    entry.lastError?.let {
                                        Text(
                                            it,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                Text(
                                    "${entry.attemptCount} ${if (entry.attemptCount == 1) "try" else "tries"}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        ) {
                            Text(
                                entry.subject.ifBlank { "(no subject)" },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
