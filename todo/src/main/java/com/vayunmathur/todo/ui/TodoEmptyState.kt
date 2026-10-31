package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconInbox
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.todo.R

enum class EmptyKind { NOTHING, SEARCH, ALL_DONE }

@Composable
fun TodoEmptyState(kind: EmptyKind, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = Modifier.size(56.dp)
        when (kind) {
            EmptyKind.NOTHING -> IconInbox(icon, tint = muted)
            EmptyKind.SEARCH -> IconSearch(icon, tint = muted)
            EmptyKind.ALL_DONE -> IconCheckCircle(icon, tint = MaterialTheme.colorScheme.primary)
        }
        val title = when (kind) {
            EmptyKind.NOTHING -> R.string.empty_title
            EmptyKind.SEARCH -> R.string.empty_search
            EmptyKind.ALL_DONE -> R.string.all_done
        }
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        val body = when (kind) {
            EmptyKind.NOTHING -> R.string.empty_body
            EmptyKind.ALL_DONE -> R.string.all_done_body
            EmptyKind.SEARCH -> null
        }
        body?.let { Text(stringResource(it), color = muted, textAlign = TextAlign.Center) }
    }
}
