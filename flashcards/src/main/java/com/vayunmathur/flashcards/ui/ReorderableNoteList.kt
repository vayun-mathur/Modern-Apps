package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vayunmathur.flashcards.ui.NoteListActions
import com.vayunmathur.flashcards.ui.NoteRow
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.animatedDp
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.library.ui.reorderDragHandle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReorderableNoteList(
    rows: List<NoteRow>,
    actions: NoteListActions,
    selection: com.vayunmathur.library.ui.SelectionState<Long>,
) {
    val listState = rememberLazyListState()
    var local by remember { mutableStateOf(rows) }
    var hasDragged by remember { mutableStateOf(false) }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index in local.indices && to.index in local.indices) {
            local = local.toMutableList().apply { add(to.index, removeAt(from.index)) }
            hasDragged = true
        }
    }
    LaunchedEffect(rows) { if (!reorderState.isAnyItemDragging) local = rows }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && hasDragged) {
            actions.reorder(local.mapIndexed { index, r -> r.note.withPosition(index.toDouble()) })
            hasDragged = false
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(local, key = { it.note.id }) { row ->
            val dragging = reorderState.draggingKey == row.note.id
            val itemModifier = if (dragging) {
                Modifier.zIndex(1f).graphicsLayer { translationY = reorderState.draggingItemTranslation }
            } else {
                Modifier.animateItem()
            }
            ReorderableItem(reorderState, key = row.note.id, modifier = itemModifier) { isDragging ->
                val elevation = animatedDp(if (isDragging) 8.dp else 0.dp)
                Surface(shadowElevation = elevation) {
                    NoteRowItem(
                        row = row,
                        selection = selection,
                        onOpen = { actions.openNote(row.note.id) },
                        onLongPress = { selection.toggle(row.note.id) },
                        modifier = Modifier.reorderDragHandle(reorderState, key = row.note.id),
                        draggable = true,
                    )
                }
            }
        }
    }
}
