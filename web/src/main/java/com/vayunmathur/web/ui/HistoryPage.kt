package com.vayunmathur.web.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.web.R
import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.ui.platform.LocalContext
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.web.Route
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.data.HistoryEntry
import com.vayunmathur.web.platform.BrowserUtils
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.clearHistory
import com.vayunmathur.web.platform.externalIntentUrl
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryPage(
    viewModel: WebViewModel,
    backStack: NavBackStack<Route>,
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    var showClearConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val grouped = remember(history, context) {
        groupByDate(context, history)
    }

    AppScaffold(
        title = stringResource(R.string.history),
        backStack = backStack,
        actions = {
            if (history.isNotEmpty()) {
                IconButton(onClick = { showClearConfirm = true }) {
                    IconDelete()
                }
            }
        },
    ) { paddingValues ->
        DesktopMaxWidthContainer(modifier = Modifier.padding(paddingValues)) {
            if (history.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.no_history_yet),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                grouped.forEach { (dateLabel, entries) ->
                    stickyHeader {
                        Text(
                            dateLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(entries, key = { it.id }) { entry ->
                        HistoryRow(
                            entry = entry,
                            onClick = {
                                viewModel.externalIntentUrl(entry.url)
                                backStack.pop()
                            }
                        )
                    }
                }
                }
            }
        }
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.clear_history),
            message = stringResource(R.string.this_will_permanently_delete_your_browsi),
            confirmLabel = stringResource(UiR.string.clear),
            dismissLabel = stringResource(UiR.string.cancel),
            onConfirm = { viewModel.clearHistory() },
            onDismiss = { showClearConfirm = false },
            destructive = true,
        )
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                entry.title.ifBlank { entry.url },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                BrowserUtils.prettyUrl(entry.url),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatTime(entry.visitedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTime(millis: Long): String {
    return try {
        Instant.fromEpochMilliseconds(millis)
            .toLocalDateTime(TimeZone.currentSystemDefault()).time
            .format(LocalTime.Format { hour(); char(':'); minute() })
    } catch (_: Exception) {
        ""
    }
}

private fun groupByDate(context: Context, entries: List<HistoryEntry>): List<Pair<String, List<HistoryEntry>>> {
    val tz = TimeZone.currentSystemDefault()
    val now = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toLocalDateTime(tz).date
    return entries.groupBy { entry ->
        val date = Instant.fromEpochMilliseconds(entry.visitedAt).toLocalDateTime(tz).date
        when (now.toEpochDays() - date.toEpochDays()) {
            // DateUtils yields a localized "Today"/"Yesterday" in every locale Android ships.
            0L, 1L -> DateUtils.getRelativeTimeSpanString(
                entry.visitedAt,
                System.currentTimeMillis(),
                DateUtils.DAY_IN_MILLIS,
            ).toString()
            else -> DateFormat.getMediumDateFormat(context).format(Date(entry.visitedAt))
        }
    }.toList()
}
