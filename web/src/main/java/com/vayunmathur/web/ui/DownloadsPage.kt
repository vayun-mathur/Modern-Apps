package com.vayunmathur.web.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.web.R
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.web.Route
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.clearAllDownloads

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsPage(
    viewModel: WebViewModel,
    backStack: NavBackStack<Route>,
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val hasLoaded by viewModel.hasLoadedDownloads.collectAsStateWithLifecycle()

    AppScaffold(
        title = stringResource(R.string.downloads),
        backStack = backStack,
        actions = {
            if (downloads.isNotEmpty()) {
                IconButton(onClick = { viewModel.clearAllDownloads() }) { IconDelete() }
            }
        },
    ) { paddingValues ->
        DesktopMaxWidthContainer(modifier = Modifier.padding(paddingValues)) {
        if (!hasLoaded && downloads.isEmpty()) {
            LoadingState(Modifier.fillMaxSize())
        } else if (downloads.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.no_downloads),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(downloads, key = { it.id }) { dl ->
                    ListItem(
                        headlineContent = { Text(dl.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text(dl.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                Text("${dl.mimeType ?: ""} • ${formatTime(dl.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        leadingContent = { IconDownload() }
                    )
                }
            }
        }
        }
    }
}

private fun formatTime(millis: Long): String {
    return try {
        Instant.fromEpochMilliseconds(millis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .format(
                LocalDateTime.Format {
                    year(); char('-'); monthNumber(); char('-'); day()
                    char(' '); hour(); char(':'); minute()
                },
            )
    } catch (_: Exception) { "" }
}
