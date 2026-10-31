package com.vayunmathur.todo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.animatedDp
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.itemMotion
import com.vayunmathur.library.ui.rememberHaptics
import com.vayunmathur.library.ui.reorderDragHandle
import com.vayunmathur.todo.R
import com.vayunmathur.todo.platform.TodoListActions
import com.vayunmathur.todo.platform.TodoListUiState
import kotlin.time.Instant

/**
 * The todo list, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoListScreen(
    state: TodoListUiState,
    actions: TodoListActions,
    /** Top-bar backup/restore buttons; empty in a preview, which has no database. */
    backupButtons: @Composable RowScope.() -> Unit = {},
) {
    BackHandler(enabled = state.searchQuery.isNotEmpty()) {
        actions.setSearchQuery("")
    }

    val haptics = rememberHaptics()
    val listState = rememberLazyListState()
    val reorder = rememberTodoReorderState(
        todos = state.todos,
        listState = listState,
        onMoveTick = { haptics.tick() },
        onCommit = { actions.upsertAll(it) },
    )

    LazyListScaffold(
        topBar = {
            TopAppBar(
                title = {
                    CommonSearchBar(
                        value = state.searchQuery,
                        onValueChange = { actions.setSearchQuery(it) },
                        placeholder = stringResource(R.string.app_name),
                        padding = PaddingValues(0.dp),
                    )
                },
                actions = backupButtons,
            )
        },
        floatingActionButton = {
            if (state.showAddButton) {
                FloatingActionButton(onClick = { actions.createTodo() }) {
                    IconAdd()
                }
            }
        },
        state = listState,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        items(reorder.rows, key = { it.id }) { todo ->
            val dragging = reorder.drag.draggingKey == todo.id
            val itemModifier = if (dragging) {
                Modifier.zIndex(1f).graphicsLayer { translationY = reorder.drag.draggingItemTranslation }
            } else {
                itemMotion()
            }
            ReorderableItem(reorder.drag, key = todo.id, modifier = itemModifier) { isDragging ->
                val elevation = animatedDp(if (isDragging) 8.dp else 0.dp)
                Surface(shadowElevation = elevation) {
                    ListItem(
                        content = { Text(todo.title.ifBlank { stringResource(R.string.untitled) }) },
                        modifier = Modifier.clickable { actions.openTodo(todo.id) },
                        supportingContent = {
                            val notesPreview = todo.notes.takeIf { it.isNotBlank() }
                            val dueText = todo.dueAt?.let { DateString.dateShort(Instant.fromEpochMilliseconds(it)) }
                            val line = listOfNotNull(notesPreview?.take(120), dueText).joinToString(" · ")
                            if (line.isNotBlank()) Text(line)
                        },
                        leadingContent = {
                            Checkbox(checked = todo.done, onCheckedChange = { actions.toggleDone(todo) })
                        },
                        trailingContent = {
                            if (state.canReorder) {
                                IconButton(
                                    modifier = Modifier.reorderDragHandle(
                                        reorderState = reorder.drag,
                                        key = todo.id,
                                        onDragStarted = { haptics.longPress() },
                                        onDragStopped = { haptics.confirm() },
                                    ),
                                    onClick = {},
                                ) {
                                    IconDragHandle()
                                }
                            }
                        },
                        elevation = ListItemDefaults.elevation(elevation = elevation),
                    )
                }
            }
        }
    }
}
