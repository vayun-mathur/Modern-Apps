package com.vayunmathur.youpipe.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.adaptiveGridCells
import com.vayunmathur.library.ui.isExpandedWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.YouPipeViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryPage(backStack: NavBackStack<Route>, youPipeViewModel: YouPipeViewModel) {
    val history by youPipeViewModel.historyVideosByRecency.collectAsState()

    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val inSelectionMode = selectedIds.isNotEmpty()

    // Back is the only way out of a selection now that there is no top bar to
    // host a cancel button.
    BackHandler(enabled = inSelectionMode) { selectedIds = emptySet() }

    // No top bar at all: clearing all history lives in Settings, and deleting a
    // selection is a FAB, so the list keeps the full height of the screen.
    // On expanded widths the list is a LazyVerticalGrid — which LazyListScaffold cannot
    // host — so that path uses a raw-scaffold exception below.
    if (isExpandedWidth()) {
        // RAW SCAFFOLD EXCEPTION: an adaptive video grid (LazyVerticalGrid of
        // adaptiveGridCells) with a selection FAB and no top bar. No shared scaffold
        // hosts a grid body.
        com.vayunmathur.library.ui.Scaffold(
            floatingActionButton = {
                if (inSelectionMode) {
                    FloatingActionButton(onClick = {
                        youPipeViewModel.deleteHistoryVideos(selectedIds.toList())
                        selectedIds = emptySet()
                    }) {
                        IconDelete()
                    }
                }
            },
        ) { paddingValues ->
            LazyVerticalGrid(
                columns = adaptiveGridCells(320.dp),
                modifier = Modifier.padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(history, key = { it.id }) { historyItem ->
                    HistoryGridCell(
                        backStack = backStack,
                        youPipeViewModel = youPipeViewModel,
                        videoInfo = historyItem.videoItem,
                        isSelected = historyItem.id in selectedIds,
                        inSelectionMode = inSelectionMode,
                        onToggleSelection = {
                            selectedIds = if (historyItem.id in selectedIds) selectedIds - historyItem.id
                            else selectedIds + historyItem.id
                        },
                    )
                }
            }
        }
        return
    }
    LazyListScaffold(
        floatingActionButton = {
            if (inSelectionMode) {
                FloatingActionButton(onClick = {
                    youPipeViewModel.deleteHistoryVideos(selectedIds.toList())
                    selectedIds = emptySet()
                }) {
                    IconDelete()
                }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {
        items(history, key = { it.id }) { historyItem ->
                val isSelected = historyItem.id in selectedIds
                VideoItem(
                    backStack, youPipeViewModel, historyItem.videoItem, true,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            if (inSelectionMode) {
                                selectedIds = if (isSelected) selectedIds - historyItem.id
                                else selectedIds + historyItem.id
                            } else {
                                backStack.add(Route.VideoPage(historyItem.videoItem.videoID))
                            }
                        },
                        onLongClick = {
                            selectedIds = selectedIds + historyItem.id
                        }
                    ),
                    backupOnClick = false,
                    trailingContent = if (inSelectionMode) {
                        {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    selectedIds = if (isSelected) selectedIds - historyItem.id
                                    else selectedIds + historyItem.id
                                }
                            )
                        }
                    } else null,
                    titleSharedKey = "youpipe-video-title-${historyItem.videoItem.videoID}",
                )
            }
    }
}

/**
 * One history entry in the expanded grid: the video stacked vertically with a selection
 * checkbox overlaid while selecting. Split from [HistoryPage]'s list row because the grid
 * cell stacks text under the thumbnail and cannot share the row layout.
 */
@Composable
private fun HistoryGridCell(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
    videoInfo: VideoInfo,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        VideoItem(
            backStack, youPipeViewModel, videoInfo, true,
            modifier = Modifier.combinedClickable(
                onClick = {
                    if (inSelectionMode) {
                        onToggleSelection()
                    } else {
                        backStack.add(Route.VideoPage(videoInfo.videoID))
                    }
                },
                onLongClick = { onToggleSelection() },
            ),
            backupOnClick = false,
            trailingContent = if (inSelectionMode) {
                {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection() },
                    )
                }
            } else null,
            // No shared-element key here: several grids can be composed at once and one
            // key needs one origin (see VideoRow's titleSharedKey note).
            vertical = true,
        )
    }
}
