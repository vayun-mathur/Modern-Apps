package com.vayunmathur.communicate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.sharedText
import kotlinx.coroutines.withContext

@Composable
internal fun MessageThreadRow(thread: SmsThread, onClick: () -> Unit, onDelete: () -> Unit = {}) {
    val context = LocalContext.current
    val title = when {
        thread.isGroup -> thread.groupTitle
            ?: thread.displayName
            ?: groupTitleFromParticipants(thread.participants)
            ?: thread.address.ifBlank { stringResource(R.string.conversation_title) }
        else -> thread.displayName ?: thread.address.ifBlank { stringResource(R.string.conversation_title) }
    }
    ListItem(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontWeight = if (thread.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .sharedText("communicate-thread-title-${thread.threadId}"),
                )
                LineBadge(thread.line, thread.subscriptionId, modifier = Modifier.padding(start = 6.dp))
                if (thread.line == com.vayunmathur.communicate.data.CommunicateLine.Rcs &&
                    com.vayunmathur.communicate.data.rcs.RcsFeature.enabled
                ) {
                    val rowId = thread.remoteId ?: thread.address
                    val encrypted by androidx.compose.runtime.produceState(
                        initialValue = false, rowId,
                    ) {
                        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
                                .groupIdFor(context, rowId) != null
                        }
                    }
                    if (encrypted) {
                        com.vayunmathur.library.ui.IconLock(
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    formatDateTime(context, thread.timestampMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        supportingContent = {
            Text(
                thread.snippet,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (thread.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        leadingContent = { ThreadAvatar(title = title, isGroup = thread.isGroup) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (thread.unreadCount > 0) UnreadBadge(thread.unreadCount)
                com.vayunmathur.library.ui.OverflowMenu(icon = { IconMoreVert() }) {
                    Item(
                        text = stringResource(com.vayunmathur.library.ui.R.string.delete),
                        leadingIcon = { IconDelete() },
                        onClick = onDelete,
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** "Alice, Bob +N" from a group's participant addresses; null if there are none. */
internal fun groupTitleFromParticipants(participants: List<String>): String? {
    if (participants.isEmpty()) return null
    val shown = participants.take(2)
    val extra = participants.size - shown.size
    return if (extra > 0) shown.joinToString(", ") + " +$extra" else shown.joinToString(", ")
}

@Composable
internal fun ThreadAvatar(title: String, isGroup: Boolean = false) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (isGroup) {
            IconGroup(tint = MaterialTheme.colorScheme.onPrimaryContainer)
        } else {
            Text(
                initialsFor(title),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun UnreadBadge(count: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            count.toString(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
