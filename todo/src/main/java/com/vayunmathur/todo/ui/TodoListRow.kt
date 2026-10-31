package com.vayunmathur.todo.ui

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vayunmathur.library.ui.Haptics
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.animatedDp
import com.vayunmathur.library.ui.itemMotion
import com.vayunmathur.library.ui.reorderDragHandle
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.TodoRow

/** Per-row behaviour the list screen supplies. */
class TodoListRowHandlers(
    val onToggle: (TodoRow) -> Unit,
    val onDelete: (TodoRow) -> Unit,
    val onClick: (TodoRow) -> Unit,
    val onLongClick: (TodoRow) -> Unit,
    /** Null when the row cannot move in that direction. */
    val onMoveUp: ((TodoRow) -> Unit)?,
    val onMoveDown: ((TodoRow) -> Unit)?,
)

/** A row as placed in the lazy list: swipe actions, drag-to-reorder and accessibility actions. */
@Composable
fun LazyItemScope.TodoListRow(
    row: TodoRow,
    display: TodoRowDisplay,
    reorder: TodoReorderState?,
    haptics: Haptics,
    handlers: TodoListRowHandlers,
) {
    val id = row.todo.id
    val dragging = reorder?.drag?.draggingKey == id
    val itemModifier = if (dragging && reorder != null) {
        Modifier.zIndex(1f).graphicsLayer { translationY = reorder.drag.draggingItemTranslation }
    } else {
        itemMotion()
    }
    val toggleLabel = stringResource(if (row.todo.done) R.string.action_mark_not_done else R.string.action_mark_done)
    val upLabel = stringResource(R.string.action_move_up)
    val downLabel = stringResource(R.string.action_move_down)
    val a11y = buildList {
        add(CustomAccessibilityAction(toggleLabel) { handlers.onToggle(row); true })
        handlers.onMoveUp?.let { up -> add(CustomAccessibilityAction(upLabel) { up(row); true }) }
        handlers.onMoveDown?.let { down -> add(CustomAccessibilityAction(downLabel) { down(row); true }) }
    }
    val callbacks = TodoRowCallbacks(
        onToggle = { handlers.onToggle(row) },
        onClick = { handlers.onClick(row) },
        onLongClick = { handlers.onLongClick(row) },
        accessibilityActions = a11y,
    )
    val content: @Composable (Boolean) -> Unit = { isDragging ->
        val elevation = animatedDp(if (isDragging) ROW_DRAG_ELEVATION else 0.dp)
        TodoSwipeRow(
            enabled = !display.selectionMode && !isDragging,
            onComplete = { handlers.onToggle(row) },
            onDelete = { handlers.onDelete(row) },
        ) {
            Surface(shadowElevation = elevation) {
                TodoRowItem(
                    row = row,
                    query = display.query,
                    now = display.now,
                    showList = display.showList,
                    selected = display.selected,
                    elevation = elevation,
                    callbacks = callbacks,
                    trailing = if (reorder != null && !display.selectionMode) {
                        { DragHandle(reorder, id, haptics) }
                    } else {
                        null
                    },
                )
            }
        }
    }
    if (reorder != null) {
        ReorderableItem(reorder.drag, key = id, modifier = itemModifier, content = content)
    } else {
        androidx.compose.foundation.layout.Box(itemModifier) { content(false) }
    }
}

/** How a row is drawn in the current screen state. */
data class TodoRowDisplay(
    val query: String,
    val now: Long,
    val showList: Boolean,
    val selected: Boolean,
    val selectionMode: Boolean,
)

@Composable
private fun DragHandle(reorder: TodoReorderState, key: Long, haptics: Haptics) {
    IconButton(
        modifier = Modifier.reorderDragHandle(
            reorderState = reorder.drag,
            key = key,
            onDragStarted = { haptics.longPress() },
            onDragStopped = { haptics.confirm() },
        ),
        onClick = {},
    ) {
        IconDragHandle()
    }
}
