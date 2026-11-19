package com.vayunmathur.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.PrimaryScrollableTabRow
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import com.vayunmathur.vpn.R
import com.vayunmathur.vpn.data.AppUsageSummary
import com.vayunmathur.vpn.data.DomainBytesSummary
import com.vayunmathur.vpn.data.DomainCountSummary

private val TABS = listOf("Top apps", "Most visited", "Top domains by data")

/**
 * The traffic leaderboards, with no ViewModel reference so they can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun LoggingContent(
    topApps: List<AppUsageSummary>,
    domainsByCount: List<DomainCountSummary>,
    domainsByBytes: List<DomainBytesSummary>,
    onDeleteAllLogs: () -> Unit = {},
    /**
     * Which leaderboard to open on. The app always starts on the first; previews set it so a
     * given tab can be captured without driving the UI to get there.
     */
    initialTab: Int = 0,
    /** Notified when the in-screen leaderboard tab changes, so the host can mirror it. */
    onTabChange: (Int) -> Unit = {},
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    // In-screen sub-tabs of the Logging destination: saveable so the chosen
    // leaderboard survives rotation/process recreation, not routed destinations.
    var tabIndex by rememberSaveable { mutableIntStateOf(initialTab) }

    AppScaffold(
        title = "",
        actions = {
            IconButton(onClick = { showDeleteDialog = true }) {
                IconDelete()
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            PrimaryScrollableTabRow(selectedTabIndex = tabIndex) {
                TABS.forEachIndexed { i, label ->
                    Tab(
                        selected = tabIndex == i,
                        onClick = { tabIndex = i; onTabChange(i) },
                        text = { Text(label, maxLines = 1, fontSize = 13.sp, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }

            when (tabIndex) {
                0 -> Leaderboard(
                    entries = topApps.map {
                        LeaderboardEntry(
                            key = it.packageName ?: it.appLabel,
                            title = it.appLabel.ifBlank { it.packageName ?: "Unknown" },
                            subtitle = it.packageName,
                            value = formatBytes(it.totalBytes),
                            weight = it.totalBytes,
                        )
                    },
                    emptyText = "No traffic yet — connect the VPN and browse",
                )
                1 -> Leaderboard(
                    entries = domainsByCount.map {
                        LeaderboardEntry(
                            key = it.domain,
                            title = it.domain,
                            subtitle = null,
                            value = "${it.totalCount} req",
                            weight = it.totalCount,
                            monospaceTitle = true,
                        )
                    },
                    emptyText = "No domains yet — connect the VPN and browse",
                )
                2 -> Leaderboard(
                    entries = domainsByBytes.map {
                        LeaderboardEntry(
                            key = it.domain,
                            title = it.domain,
                            subtitle = null,
                            value = formatBytes(it.totalBytes),
                            weight = it.totalBytes,
                            monospaceTitle = true,
                        )
                    },
                    emptyText = "No domains yet — connect the VPN and browse",
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_all_logs)) },
            text = { Text(stringResource(R.string.this_will_permanently_remove_all_connect)) },
            confirmButton = {
                Button(onClick = { onDeleteAllLogs(); showDeleteDialog = false }) { Text(stringResource(UiR.string.delete)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteDialog = false }) { Text(stringResource(UiR.string.cancel)) }
            }
        )
    }
}

private data class LeaderboardEntry(
    val key: String,
    val title: String,
    val subtitle: String?,
    val value: String,
    val weight: Long,
    val monospaceTitle: Boolean = false,
)

@Composable
private fun Leaderboard(entries: List<LeaderboardEntry>, emptyText: String) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        return
    }
    // Bars are relative to the leader, which is the first row since every query orders descending.
    val top = entries.first().weight.coerceAtLeast(1L)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
            LeaderboardRow(index + 1, entry, entry.weight.toFloat() / top.toFloat())
        }
    }
}

@Composable
private fun LeaderboardRow(rank: Int, entry: LeaderboardEntry, fraction: Float) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "$rank",
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.End,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    entry.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = if (entry.monospaceTitle) FontFamily.Monospace else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.subtitle != null) {
                    Text(
                        entry.subtitle,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
            Text(
                entry.value,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val locale = java.util.Locale.getDefault()
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(locale, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(locale, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(locale, "%.2f GB", gb)
}
