package com.vayunmathur.email.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.text.htmlEncode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.R
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.OutboxEntry
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.AppMessages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutboxScreen(
    viewModel: EmailViewModel,
    onBack: () -> Unit,
) {
    val outbox by viewModel.outbox.collectAsStateWithLifecycle(emptyList())
    val hasLoaded by viewModel.hasLoadedOutbox.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current

    AppScaffold(
        title = stringResource(R.string.outbox),
        onNavigateBack = onBack,
        actions = {
            if (outbox.isNotEmpty()) {
                TextButton(onClick = {
                    viewModel.send.sendOutboxNow(context)
                    AppMessages.show(resources.getQuantityString(R.plurals.retrying_pending_messages, outbox.size, outbox.size))
                }) { Text(stringResource(R.string.send_now)) }
            }
        },
    ) { padding ->
        if (!hasLoaded && outbox.isEmpty()) {
            LoadingState(Modifier.padding(padding).fillMaxSize())
        } else if (outbox.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.outbox_is_empty),
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                items(outbox, key = { it.id }) { entry ->
                    OutboxRow(
                        entry = entry,
                        onDelete = {
                            viewModel.send.deleteOutboxEntry(entry)
                            AppMessages.show(resources.getString(R.string.deleted_from_outbox))
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun OutboxRow(entry: OutboxEntry, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.subject.ifBlank { stringResource(R.string.no_subject) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.from, entry.accountEmail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.to_1, entry.to),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) { IconDelete() }
        }
        if (entry.body.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                entry.body.lineSequence().firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
        }
        if (entry.lastError != null || entry.attemptCount > 0) {
            Spacer(Modifier.height(8.dp))
            val statusColor =
                if (entry.lastError != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            val failedText = if (entry.lastError != null) stringResource(R.string.failed, entry.lastError) else ""
            val attemptText = if (entry.attemptCount > 0) pluralStringResource(R.plurals.attempts, entry.attemptCount, entry.attemptCount) else ""
            Text(
                buildString {
                    if (failedText.isNotEmpty()) append(failedText)
                    if (attemptText.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(attemptText)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
            )
        }
    }
}

/** The signature block (HTML) appended to an outgoing message body, or "" if none. */
internal fun signatureBlockHtml(acc: com.vayunmathur.email.data.EmailAccount?): String {
    val s = acc?.signature?.trim().orEmpty()
    if (s.isEmpty()) return ""
    val escaped = s.htmlEncode().replace("\n", "<br>")
    return "<br><br>-- <br>$escaped"
}
