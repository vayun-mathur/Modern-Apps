package com.vayunmathur.todo.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ui.ReorderableLazyListState
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.domain.Positions

/** Rows as currently drawn (moved locally while a drag is in flight) plus the drag state. */
class TodoReorderState internal constructor(
    val drag: ReorderableLazyListState,
    private val rowsState: androidx.compose.runtime.State<List<Todo>>,
) {
    val rows: List<Todo> get() = rowsState.value
}

/**
 * Drag-to-reorder over [todos] (already in display order). Each move rewrites only the moved
 * row's position; [onCommit] receives the moved rows once the drag ends.
 */
@Composable
fun rememberTodoReorderState(
    todos: List<Todo>,
    listState: LazyListState,
    /** Lazy items drawn before the first row (headers, the add field). */
    indexOffset: Int,
    onMoveTick: () -> Unit,
    onCommit: (List<Todo>) -> Unit,
): TodoReorderState {
    val rows = remember { mutableStateOf(todos) }
    var moved by remember { mutableStateOf(emptySet<Long>()) }

    val drag = rememberReorderableLazyListState(listState) { from, to ->
        val current = rows.value
        val fromRow = from.index - indexOffset
        val toRow = to.index - indexOffset
        if (fromRow in current.indices && toRow in current.indices) {
            val position = Positions.forMove(current.map { it.position }, fromRow, toRow)
            val list = current.toMutableList()
            val item = list.removeAt(fromRow).withPosition(position)
            list.add(toRow, item)
            rows.value = list
            moved = moved + item.id
            onMoveTick()
        }
    }

    LaunchedEffect(todos) {
        if (!drag.isAnyItemDragging) rows.value = todos
    }
    val dragging = drag.isAnyItemDragging
    LaunchedEffect(dragging) {
        if (!dragging && moved.isNotEmpty()) {
            onCommit(rows.value.filter { it.id in moved })
            moved = emptySet()
        }
    }
    return remember(drag) { TodoReorderState(drag, rows) }
}
