package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconAlarm
import com.vayunmathur.library.ui.IconChecklist
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.IconRepeat
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.TodoRow

/** The small line under a task: due, repeat, reminder, subtasks, list, tags, blocked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodoRowMeta(row: TodoRow, now: Long, showList: Boolean, modifier: Modifier = Modifier) {
    val todo = row.todo
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelMedium
    val iconSize = Modifier.size(14.dp)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        todo.dueAt?.let { due ->
            val color = if (row.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            Text(dueLabel(due, todo.dueAllDay, now), color = color, style = style)
        }
        if (todo.recurrence != null) IconRepeat(iconSize, tint = muted)
        if (todo.reminderAt != null && !todo.done) IconAlarm(iconSize, tint = muted)
        if (row.subtasksTotal > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconChecklist(iconSize, tint = muted)
                Text(stringResource(R.string.subtask_progress, row.subtasksDone, row.subtasksTotal), color = muted, style = style)
            }
        }
        if (showList && row.list != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val dot = row.list.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Text(row.list.name, color = muted, style = style)
            }
        }
        todo.tags.forEach { Text("#$it", color = MaterialTheme.colorScheme.tertiary, style = style) }
        if (row.blocked) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconLock(iconSize, tint = muted)
                Text(stringResource(R.string.blocked), color = muted, style = style)
            }
        }
    }
}

/** Has anything to draw — callers skip the meta line entirely otherwise. */
fun TodoRow.hasMeta(showList: Boolean): Boolean =
    todo.dueAt != null || todo.recurrence != null || (todo.reminderAt != null && !todo.done) ||
        subtasksTotal > 0 || (showList && list != null) || todo.tags.isNotEmpty() || blocked
