package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.madeveloper.domain.DevPresentation
import com.vayunmathur.madeveloper.domain.GitCommit

/** One commit as a tappable list row: subject, author, relative time and short hash. */
@Composable
fun CommitRow(commit: GitCommit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val now = remember { System.currentTimeMillis() }
    val relative = DevPresentation.relativeTimeLabel(commit.time, now)
    ListItem(
        headlineContent = {
            Text(commit.subject.ifBlank { commit.shortSha }, maxLines = 2)
        },
        supportingContent = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        commit.shortSha.ifBlank { DevPresentation.shortSha(commit.sha) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (commit.author.isNotBlank()) {
                        Text(
                            " · " + commit.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (relative.isNotBlank()) {
                        Text(
                            " · " + relative,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        trailingContent = { IconChevronRight() },
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 4.dp),
    )
}
