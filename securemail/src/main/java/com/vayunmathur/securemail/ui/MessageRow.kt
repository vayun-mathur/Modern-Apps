package com.vayunmathur.securemail.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDeleteForever
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.IconMarkRead
import com.vayunmathur.library.ui.IconMarkUnread
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.SwipeActionSide
import com.vayunmathur.library.ui.SwipeActionsBox
import com.vayunmathur.library.ui.Text
import com.vayunmathur.securemail.data.StoredMessage
import java.text.DateFormat
import java.util.Date

/**
 * One folder-list row, email-app style: unread stripe, bold subject when
 * unread, sender + one-line preview, date pinned top-right and a fixed trust
 * icon slot bottom-right. Swipe right toggles read, swipe left trashes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    msg: StoredMessage,
    showRecipient: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleRead: () -> Unit,
    onSwipeDelete: () -> Unit,
    inTrash: Boolean,
) {
    SwipeActionsBox(
        onStartToEnd = onToggleRead,
        onEndToStart = onSwipeDelete,
        background = { side ->
            when (side) {
                SwipeActionSide.StartToEnd -> Box(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { if (msg.isRead) IconMarkUnread() else IconMarkRead() }
                SwipeActionSide.EndToStart -> Box(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) { if (inTrash) IconDeleteForever() else IconDelete() }
                SwipeActionSide.None -> Unit
            }
        },
    ) {
        Row(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surface,
                )
                .height(IntrinsicSize.Min),
        ) {
            Surface(
                modifier = Modifier.width(4.dp).fillMaxHeight(),
                color = if (msg.isRead) Color.Transparent else MaterialTheme.colorScheme.primary,
            ) {}
            ListItem(
                supportingContent = { RowSupport(msg, showRecipient) },
                modifier = Modifier.weight(1f),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            ) {
                Text(
                    msg.decryptedSubject?.ifBlank { "(no subject)" } ?: "Encrypted message",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (msg.isRead) FontWeight.Normal else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RowTrailing(msg)
        }
    }
}

@Composable
private fun RowSupport(msg: StoredMessage, showRecipient: Boolean) {
    Column {
        Text(
            if (showRecipient) "To: ${msg.toJson.ifBlank { "(unknown)" }}" else msg.from.ifBlank { "(encrypted sender)" },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val preview = remember(msg.decryptedBody) {
            msg.decryptedBody?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        }
        Text(
            preview ?: if (msg.unencrypted) "" else "Open to decrypt",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RowTrailing(msg: StoredMessage) {
    val context = LocalContext.current
    val date = remember(msg.dateMillis) {
        if (DateUtils.isToday(msg.dateMillis)) {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(msg.dateMillis))
        } else {
            val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val msgYear = java.util.Calendar.getInstance().apply { timeInMillis = msg.dateMillis }
                .get(java.util.Calendar.YEAR)
            val yearFlag = if (msgYear == thisYear) DateUtils.FORMAT_NO_YEAR else 0
            DateUtils.formatDateTime(
                context,
                msg.dateMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or yearFlag,
            )
        }
    }
    // Fills the row height: date pinned to the top, a fixed 16dp icon slot
    // pinned to the bottom (empty when there's nothing to flag) so every row
    // lines up the same way.
    Column(
        modifier = Modifier.fillMaxHeight().padding(top = 14.dp, bottom = 12.dp, end = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        Text(date, style = MaterialTheme.typography.labelSmall)
        Box(Modifier.size(16.dp)) {
            when {
                msg.unencrypted -> IconWarning(Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                msg.decryptedBody == null -> IconLock(Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
