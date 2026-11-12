package com.vayunmathur.email.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.accountColor
import com.vayunmathur.email.data.senderDisplayName
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.contentColorOn

/**
 * Compact, selectable list of a thread's messages (sender, date, snippet),
 * shown above the open message when the thread has more than one. Tapping a
 * row opens that message in the pane below. It scrolls on its own and is never
 * an ancestor of the message body, so it cannot compete with the body's
 * scrolling (#900).
 */
@Composable
fun ThreadMessageStrip(
    messages: List<EmailMessage>,
    selectedKey: String,
    onSelect: (EmailMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialIndex = messages.indexOfFirst { it.threadKey() == selectedKey }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    LazyColumn(modifier = modifier, state = listState) {
        items(messages, key = { it.threadKey() }) { msg ->
            ThreadMessageRow(msg = msg, selected = msg.threadKey() == selectedKey, onClick = { onSelect(msg) })
        }
    }
}

@Composable
private fun ThreadMessageRow(msg: EmailMessage, selected: Boolean, onClick: () -> Unit) {
    val senderName = senderDisplayName(msg.from).ifEmpty { msg.from }
    val avatarColor = Color(accountColor(msg.accountEmail))
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Surface(color = background, modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.Tab, onClick = onClick)) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = avatarColor, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = senderName.take(1).uppercase(), color = contentColorOn(avatarColor), style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (msg.isRead) FontWeight.Normal else FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(text = "  \u2022  ${msg.date.substringBeforeLast(":")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (msg.peekContent.isNotBlank()) {
                    Text(text = msg.peekContent, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
