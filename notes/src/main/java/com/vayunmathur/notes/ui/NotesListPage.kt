package com.vayunmathur.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.noteDbConfigs
import com.vayunmathur.notes.util.NotesViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesListPage(backStack: NavBackStack<Route>, viewModel: NotesViewModel) {
    val context = LocalContext.current
    val notes by viewModel.notes.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(notes, searchQuery) {
        if (searchQuery.isBlank()) notes
        else notes.filter { it.toString().contains(searchQuery, true) }
    }

    BackHandler(enabled = searchQuery.isNotEmpty()) {
        searchQuery = ""
    }

    val hapticFeedback = LocalHapticFeedback.current
    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }

    val listState = rememberLazyListState()
    var localData by remember { mutableStateOf(filtered) }

    val selectedIndices by remember {
        derivedStateOf {
            localData.mapIndexedNotNull { index, item ->
                if (item.id in selectedIds) index else null
            }
        }
    }
    val isContiguous by remember {
        derivedStateOf {
            selectedIndices.isEmpty() || selectedIndices.size == 1 ||
                (selectedIndices.last() - selectedIndices.first() == selectedIndices.size - 1)
        }
    }

    var hasDragged by remember { mutableStateOf(false) }

    val reorderState = rememberReorderableLazyListState(listState, onMove = { from, to ->
        val fromIdx = from.index
        val toIdx = to.index
        if (fromIdx in localData.indices && toIdx in localData.indices) {
            val mutableList = localData.toMutableList()
            val draggedItem = localData[fromIdx]
            if (isSelectionMode && isContiguous && draggedItem.id in selectedIds) {
                val selectedInOrder = selectedIndices.map { localData[it] }
                mutableList.removeAll { it.id in selectedIds }
                val targetItem = localData[toIdx]
                var insertIdx = mutableList.indexOfFirst { it.id == targetItem.id }
                if (insertIdx == -1) insertIdx = 0
                if (toIdx > fromIdx) insertIdx++
                val prevPos = mutableList.getOrNull(insertIdx - 1)?.position
                val nextPos = mutableList.getOrNull(insertIdx)?.position
                val startPos = prevPos ?: ((nextPos ?: 0.0) - 100.0)
                val endPos = nextPos ?: (startPos + 100.0)
                val step = (endPos - startPos) / (selectedInOrder.size + 1)
                val movedItems = selectedInOrder.mapIndexed { index, item ->
                    item.withPosition(startPos + step * (index + 1))
                }
                mutableList.addAll(insertIdx, movedItems)
                localData = mutableList
            } else if (!isSelectionMode) {
                val prevIdx = if (toIdx > fromIdx) toIdx else toIdx - 1
                val nextIdx = if (toIdx > fromIdx) toIdx + 1 else toIdx
                val prevPos = localData.getOrNull(prevIdx)?.position
                val nextPos = localData.getOrNull(nextIdx)?.position
                val resultItemPosition = when {
                    prevPos == null -> (nextPos ?: 0.0) - 50.0
                    nextPos == null -> prevPos + 50.0
                    else -> (prevPos + nextPos) / 2.0
                }
                val movedItem = mutableList.removeAt(fromIdx).withPosition(resultItemPosition)
                mutableList.add(toIdx, movedItem)
                localData = mutableList
            }
            hasDragged = true
            hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    })

    LaunchedEffect(filtered) {
        if (!reorderState.isAnyItemDragging) {
            localData = filtered.sortedBy { it.position }
        }
    }

    val isDragging = reorderState.isAnyItemDragging
    LaunchedEffect(isDragging) {
        if (!isDragging && hasDragged) {
            viewModel.upsertAll(localData)
            hasDragged = false
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text(selectedIds.size.toString()) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds.clear() }) {
                            IconClose()
                        }
                    },
                    actions = {
                        val selectedNotes = localData.filter { it.id in selectedIds }
                        IconButton(onClick = {
                            selectedNotes.forEach { viewModel.delete(it) }
                            selectedIds.clear()
                        }) {
                            IconDelete()
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        CommonSearchBar(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = "Notes",
                            padding = PaddingValues(0.dp)
                        )
                    },
                    actions = {
                        BackupButtons(
                            dbConfigs = remember { noteDbConfigs(context) },
                            dbCodec = SqlCipherDbCodec,
                            extraFiles = emptyList()
                        )
                    }
                )
            }
        },
        floatingActionButton = {
            if (backStack.last() !is Route.Note && !isSelectionMode) {
                FloatingActionButton(onClick = { backStack.add(Route.Note(0)) }) {
                    IconAdd()
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues
        ) {
            items(localData, key = { it.id }) { note ->
                ReorderableItem(reorderState, key = note.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                    val isSelected = note.id in selectedIds
                    Surface(Modifier.animateItem(), shadowElevation = elevation) {
                        ListItem(
                            content = { Text(note.title) },
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (isSelectionMode) {
                                        if (isSelected) selectedIds.remove(note.id)
                                        else selectedIds.add(note.id)
                                    } else {
                                        backStack.add(Route.Note(note.id))
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        selectedIds.add(note.id)
                                    }
                                }
                            ),
                            supportingContent = {
                                Text(note.content.substringBefore('\n').take(40))
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (localData.size > 1 && selectedIds.size < localData.size && (!isSelectionMode || isContiguous)) {
                                        IconButton(
                                            modifier = Modifier.draggableHandle(
                                                onDragStarted = {
                                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                                },
                                                onDragStopped = {
                                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                                },
                                            ),
                                            onClick = {},
                                        ) {
                                            IconDragHandle()
                                        }
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            ),
                            elevation = ListItemDefaults.elevation(elevation = elevation),
                        )
                    }
                }
            }
        }
    }
}
