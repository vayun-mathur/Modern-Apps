package com.vayunmathur.todo.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.todo.wear.R
import com.vayunmathur.todo.wear.data.WearTodoStore
import com.vayunmathur.todo.wear.domain.overlayPendingTasks
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One phone task-detail page at watch size: notes, due/list/priority/tags/
 * recurrence, subtasks (checkable), and the two-way actions the phone UI
 * has that fit a watch — complete/reopen, snooze, edit, delete (via its
 * confirmation route). Every action queues an op for the phone.
 */
@Composable
fun WearTodoTaskScreen(
    store: WearTodoStore,
    syncId: String,
    pageKey: String,
    onEdit: (String) -> Unit,
    onConfirmDelete: (String) -> Unit,
    // Subtasks toggle done inline (AGENTS.md); there is no subtask detail route.
    onBack: () -> Unit,
) {
    val pagesMap by store.pages.collectAsState()
    val pending by store.pendingOps.collectAsState()
    val allTasks = overlayPendingTasks(store.pagesFor(pageKey).flatMap { it.tasks }, pending)
    val task = allTasks.firstOrNull { it.syncId == syncId }
        // A subtask opened from its parent renders from the parent's entry.
        ?: allTasks.firstOrNull { parent -> parent.subtasks.any { it.syncId == syncId } }
            ?.let { parent ->
                val sub = parent.subtasks.first { it.syncId == syncId }
                com.vayunmathur.todo.wear.domain.WearTodoTask(
                    syncId = sub.syncId,
                    title = sub.title,
                    done = sub.done,
                )
            }
    WearTodoTaskContent(task = task, onToggleSubtask = { id, done -> store.setDone(id, done) }, onSetDone = { id, done -> store.setDone(id, done) }, onSnooze = { id, tomorrow -> store.snooze(id, tomorrow) }, onEdit = onEdit, onConfirmDelete = onConfirmDelete, onBack = onBack)
}

/** Stateless task detail behind [WearTodoTaskScreen]: the task is a parameter for previews. */
@Composable
internal fun WearTodoTaskContent(
    task: com.vayunmathur.todo.wear.domain.WearTodoTask?,
    onToggleSubtask: (String, Boolean) -> Unit,
    onSetDone: (String, Boolean) -> Unit,
    onSnooze: (String, Boolean) -> Unit,
    onEdit: (String) -> Unit,
    onConfirmDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        if (task == null) {
            item {
                Text(
                    stringResource(R.string.wear_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            item {
                Text(
                    task.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
            val meta = listOfNotNull(
                task.dueAt?.let { due ->
                    val date = Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault())
                    "Due " + DateTimeFormatter.ofPattern("EEE d MMM").format(date)
                },
                task.listName,
                if (task.priority > 0) "Priority ${task.priority}" else null,
                task.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
                task.recurrence?.let { "Repeats" },
                if (task.blocked) "Blocked" else null,
            )
            if (meta.isNotEmpty()) {
                item {
                    Text(
                        meta.joinToString(" · "),
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            if (task.notes.isNotBlank()) {
                item {
                    Text(
                        task.notes,
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            if (task.notesTruncated) {
                item {
                    Text(
                        stringResource(R.string.wear_notes_truncated),
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
            }
            if (task.subtasks.isNotEmpty()) {
                items(task.subtasks, key = { it.syncId }) { sub ->
                    Chip(
                        onClick = { onToggleSubtask(sub.syncId, !sub.done) },
                        label = {
                            Text(
                                (if (sub.done) "✓ " else "") + sub.title,
                                maxLines = 1,
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }
            item {
                Chip(
                    onClick = { onSetDone(task.syncId, !task.done) },
                    label = {
                        Text(
                            stringResource(
                                if (task.done) R.string.wear_uncomplete else R.string.wear_complete,
                            ),
                            maxLines = 1,
                        )
                    },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            item {
                Chip(
                    onClick = { onSnooze(task.syncId, false) },
                    label = { Text(stringResource(R.string.wear_snooze_hour), maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            item {
                Chip(
                    onClick = { onSnooze(task.syncId, true) },
                    label = {
                        Text(stringResource(R.string.wear_snooze_tomorrow), maxLines = 1)
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            item {
                Chip(
                    onClick = { onEdit(task.syncId) },
                    label = { Text(stringResource(R.string.wear_edit), maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            item {
                Chip(
                    onClick = { onConfirmDelete(task.syncId) },
                    label = { Text(stringResource(R.string.wear_delete), maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        item {
            Chip(
                onClick = onBack,
                label = { Text(stringResource(R.string.wear_back), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
