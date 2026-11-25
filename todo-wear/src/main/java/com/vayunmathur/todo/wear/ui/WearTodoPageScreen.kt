package com.vayunmathur.todo.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.vayunmathur.todo.wear.sync.WearLinkBridge
import kotlinx.coroutines.launch

/**
 * One phone `Route.TodoList` page at watch size: cached slices of the view
 * in phone order (group labels kept), pending ops overlaid, with explicit
 * "load more" paging — the next slice is requested from the phone and the
 * cached slices keep rendering while it is in flight or unreachable.
 */
@Composable
fun WearTodoPageScreen(
    store: WearTodoStore,
    pageKey: String,
    onOpenTask: (String) -> Unit,
    onQuickAdd: () -> Unit,
    onBack: () -> Unit,
) {
    val pagesMap by store.pages.collectAsState()
    val pending by store.pendingOps.collectAsState()
    val catalog by store.catalog.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val slices = store.pagesFor(pageKey)
    val title = slices.firstOrNull()?.title
        ?: catalog.views.firstOrNull { it.key == pageKey }?.title
        ?: pageKey
    val allTasks = overlayPendingTasks(slices.flatMap { it.tasks }, pending)
    val last = slices.lastOrNull()
    val hasMore = last?.hasMore == true

    LaunchedEffect(pageKey) {
        if (slices.isEmpty()) WearLinkBridge.requestPage(context, pageKey, 0)
    }
    WearTodoPageContent(
        title = title,
        tasks = allTasks,
        slicesEmpty = slices.isEmpty(),
        hasMore = hasMore,
        onOpenTask = onOpenTask,
        onQuickAdd = onQuickAdd,
        onLoadMore = { scope.launch { WearLinkBridge.requestPage(context, pageKey, last?.nextOffset ?: 0) } },
        onBack = onBack,
    )
}

/** Stateless page behind [WearTodoPageScreen]: title/tasks/paging are parameters for previews. */
@Composable
internal fun WearTodoPageContent(
    title: String,
    tasks: List<com.vayunmathur.todo.wear.domain.WearTodoTask>,
    slicesEmpty: Boolean,
    hasMore: Boolean,
    onOpenTask: (String) -> Unit,
    onQuickAdd: () -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
) {
    val allTasks = tasks

    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item {
            Text(
                title,
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        item {
            Chip(
                onClick = onQuickAdd,
                label = { Text(stringResource(R.string.wear_quick_add), maxLines = 1) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        if (slicesEmpty) {
            item {
                Text(
                    stringResource(R.string.wear_no_page),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else if (allTasks.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            items(allTasks, key = { it.syncId }) { task ->
                Chip(
                    onClick = { onOpenTask(task.syncId) },
                    label = {
                        Text(
                            (if (task.done) "✓ " else "") + task.title.ifBlank { "Untitled" },
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = {
                        val meta = listOfNotNull(
                            task.groupLabel.takeIf { it.isNotBlank() },
                            task.listName,
                            if (task.priority > 0) "P${task.priority}" else null,
                            if (task.overdue) "Overdue" else null,
                        ).joinToString(" · ")
                        Text(meta, maxLines = 1)
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        if (hasMore) {
            item {
                Chip(
                    onClick = onLoadMore,
                    label = { Text(stringResource(R.string.wear_load_more), maxLines = 1) },
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
