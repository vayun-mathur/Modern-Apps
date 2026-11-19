package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.adaptiveGridCells
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.library.ui.reorderDragHandle
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.animatedDp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.YouPipePlaylistOps
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/**
 * The videos inside one playlist. Items can be reordered by drag handle (persisted via
 * [YouPipePlaylistOps.reorderPlaylistItems]) and removed via long-press selection. The whole
 * playlist can be deleted from here unless it is the mandatory Watch later playlist.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailPage(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
    playlistId: Long,
) {
    val playlist by remember(playlistId) { youPipeViewModel.playlistById(playlistId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val itemsFlow = remember(playlistId) { youPipeViewModel.playlistItemsFor(playlistId) }
    val playlistItems by itemsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val mandatory = playlist?.mandatory == true
    val title = when {
        mandatory -> stringResource(R.string.playlist_watch_later)
        else -> playlist?.name.orEmpty()
    }

    val hapticFeedback = LocalHapticFeedback.current
    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }

    val listState = rememberLazyListState()
    var localData by remember { mutableStateOf(playlistItems) }
    var hasDragged by remember { mutableStateOf(false) }

    val reorderState = rememberReorderableLazyListState(listState, onMove = { from, to ->
        val fromIdx = from.index
        val toIdx = to.index
        if (fromIdx in localData.indices && toIdx in localData.indices) {
            val mutableList = localData.toMutableList()
            val prevIdx = if (toIdx > fromIdx) toIdx else toIdx - 1
            val nextIdx = if (toIdx > fromIdx) toIdx + 1 else toIdx
            val prevPos = localData.getOrNull(prevIdx)?.position
            val nextPos = localData.getOrNull(nextIdx)?.position
            val resultPosition = when {
                prevPos == null -> (nextPos ?: 0.0) - 50.0
                nextPos == null -> prevPos + 50.0
                else -> (prevPos + nextPos) / 2.0
            }
            val movedItem = mutableList.removeAt(fromIdx).withPosition(resultPosition)
            mutableList.add(toIdx, movedItem)
            localData = mutableList
            hasDragged = true
            hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    })

    LaunchedEffect(playlistItems) {
        if (!reorderState.isAnyItemDragging) {
            localData = playlistItems
        }
    }

    val isDragging = reorderState.isAnyItemDragging
    LaunchedEffect(isDragging) {
        if (!isDragging && hasDragged) {
            youPipeViewModel.playlistOps.reorderPlaylistItems(localData)
            hasDragged = false
        }
    }

    AppScaffold(
        title = {
            if (isSelectionMode) {
                Text(stringResource(R.string.selected_count, selectedIds.size))
            } else {
                Text(title, modifier = Modifier.sharedText("youpipe-playlist-name-$playlistId"))
            }
        },
        navigationIcon = {
            if (isSelectionMode) {
                IconButton(onClick = { selectedIds.clear() }) { IconClose() }
            } else {
                IconNavigation(backStack)
            }
        },
        actions = {
            if (isSelectionMode) {
                IconButton(onClick = {
                    localData.filter { it.id in selectedIds }
                        .forEach { youPipeViewModel.playlistOps.removeFromPlaylist(it) }
                    selectedIds.clear()
                }) {
                    IconDelete()
                }
            } else if (!mandatory) {
                IconButton(onClick = {
                    playlist?.let { youPipeViewModel.playlistOps.deletePlaylist(it) }
                    backStack.pop()
                }) {
                    IconDelete()
                }
            }
        },
    ) { paddingValues ->
        if (localData.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.playlist_empty))
            }
        } else if (isExpandedWidth() && !isSelectionMode && !isDragging) {
            // Desktop, settled state: the playlist fills the window with an adaptive grid
            // of stacked cards instead of one stretched column. Drag-reorder and
            // multiselect are pointer-ordered interactions, so while either is active the
            // screen keeps the compact reorderable list below.
            LazyVerticalGrid(
                columns = adaptiveGridCells(320.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = paddingValues,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(localData, key = { it.id }) { item ->
                    VideoItem(
                        backStack = backStack,
                        youPipeViewModel = youPipeViewModel,
                        videoInfo = item.videoItem,
                        showAuthor = true,
                        modifier = Modifier.animateItem(),
                        onClick = { backStack.add(Route.VideoPage(item.videoItem.videoID)) },
                        // No shared-element key: the compact list composes the same videos
                        // and one key needs one origin (see VideoRow's titleSharedKey note).
                        vertical = true,
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = paddingValues
            ) {
                items(localData, key = { it.id }) { item ->
                    val isSelected = item.id in selectedIds
                    val dragging = reorderState.draggingKey == item.id
                    val itemMod = if (dragging) {
                        Modifier
                            .zIndex(1f)
                            .graphicsLayer { translationY = reorderState.draggingItemTranslation }
                    } else {
                        Modifier.animateItem()
                    }
                    ReorderableItem(reorderState, key = item.id, modifier = itemMod) { isDrag ->
                        val elevation = animatedDp(if (isDrag) 8.dp else 0.dp)
                        Surface(shadowElevation = elevation) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isSelectionMode) {
                                    SelectionIndicator(isSelected)
                                }
                                VideoItem(
                                    backStack = backStack,
                                    youPipeViewModel = youPipeViewModel,
                                    videoInfo = item.videoItem,
                                    showAuthor = true,
                                    modifier = Modifier.weight(1f).combinedClickable(
                                        onClick = {
                                            if (isSelectionMode) {
                                                if (isSelected) selectedIds.remove(item.id)
                                                else selectedIds.add(item.id)
                                            } else {
                                                backStack.add(Route.VideoPage(item.videoItem.videoID))
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode) selectedIds.add(item.id)
                                        }
                                    ),
                                    onClick = null,
                                    backupOnClick = false,
                                    titleSharedKey = "youpipe-video-title-${item.videoItem.videoID}",
                                    trailingContent = {
                                        if (!isSelectionMode && localData.size > 1) {
                                            IconButton(
                                                modifier = Modifier.reorderDragHandle(
                                                    reorderState = reorderState,
                                                    key = item.id,
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
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
