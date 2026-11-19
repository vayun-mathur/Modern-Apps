package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.library.ui.reorderDragHandle
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.animatedDp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.youpipe.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/**
 * The Saved library: a list of playlists. The first two rows are always pinned —
 * Downloads (the existing [DownloadedVideosPage], not a real playlist) and the mandatory
 * Watch later playlist — followed by the user's own reorderable playlists.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SavedPage(backStack: NavBackStack<Route>, youPipeViewModel: YouPipeViewModel) {
    val playlists by youPipeViewModel.playlists.collectAsStateWithLifecycle()
    val downloadedVideos by youPipeViewModel.downloadedVideos.collectAsStateWithLifecycle()
    val allPlaylistItems by youPipeViewModel.allPlaylistItems.collectAsStateWithLifecycle()

    val watchLater = playlists.firstOrNull { it.mandatory }
    val userPlaylists = playlists.filter { !it.mandatory }

    val counts = remember(allPlaylistItems) { allPlaylistItems.groupingBy { it.playlistId }.eachCount() }

    // Rows rendered above the reorderable section: Downloads (always) + Watch later (if seeded).
    val pinnedCount = 1 + (if (watchLater != null) 1 else 0)

    val hapticFeedback = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var localData by remember { mutableStateOf(userPlaylists) }
    var hasDragged by remember { mutableStateOf(false) }

    val reorderState = rememberReorderableLazyListState(listState, onMove = { from, to ->
        val fromIdx = from.index - pinnedCount
        val toIdx = to.index - pinnedCount
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

    LaunchedEffect(userPlaylists) {
        if (!reorderState.isAnyItemDragging) {
            localData = userPlaylists
        }
    }

    val isDragging = reorderState.isAnyItemDragging
    LaunchedEffect(isDragging) {
        if (!isDragging && hasDragged) {
            youPipeViewModel.playlistOps.reorderPlaylists(localData)
            hasDragged = false
        }
    }

    LazyListScaffold(
        title = stringResource(UiR.string.saved),
        state = listState,
        floatingActionButton = {
            FloatingActionButton(onClick = { backStack.add(Route.CreatePlaylist) }) {
                IconAdd()
            }
        },
    ) {
            // Pinned: Downloads (the existing downloads UI, not a real playlist).
            item(key = "pinned-downloads") {
                ListItem(
                    leadingContent = { IconDownload() },
                    content = { Text(stringResource(R.string.title_downloads)) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.playlist_video_count, downloadedVideos.size, downloadedVideos.size))
                    },
                    modifier = Modifier.clickable { backStack.add(Route.Downloads) },
                )
            }

            // Pinned: the mandatory Watch later playlist.
            if (watchLater != null) {
                item(key = "pinned-watch-later") {
                    ListItem(
                        leadingContent = { IconList() },
                        content = {
                            Text(
                                stringResource(R.string.playlist_watch_later),
                                modifier = Modifier.sharedText("youpipe-playlist-name-${watchLater.id}"),
                            )
                        },
                        supportingContent = {
                            Text(pluralStringResource(R.plurals.playlist_video_count, counts[watchLater.id] ?: 0, counts[watchLater.id] ?: 0))
                        },
                        modifier = Modifier.clickable {
                            backStack.add(Route.PlaylistDetail(watchLater.id))
                        },
                    )
                }
            }

            // User playlists: reorderable via drag handle.
            items(localData, key = { it.id }) { playlist ->
                val dragging = reorderState.draggingKey == playlist.id
                val itemMod = if (dragging) {
                    Modifier
                        .zIndex(1f)
                        .graphicsLayer { translationY = reorderState.draggingItemTranslation }
                } else {
                    Modifier.animateItem()
                }
                ReorderableItem(reorderState, key = playlist.id, modifier = itemMod) { isDrag ->
                    val elevation = animatedDp(if (isDrag) 8.dp else 0.dp)
                    Surface(shadowElevation = elevation) {
                        ListItem(
                            leadingContent = { IconList() },
                            content = {
                                Text(
                                    playlist.name,
                                    modifier = Modifier.sharedText("youpipe-playlist-name-${playlist.id}"),
                                )
                            },
                            supportingContent = {
                                Text(pluralStringResource(R.plurals.playlist_video_count, counts[playlist.id] ?: 0, counts[playlist.id] ?: 0))
                            },
                            trailingContent = {
                                if (localData.size > 1) {
                                    IconButton(
                                        modifier = Modifier.reorderDragHandle(
                                            reorderState = reorderState,
                                            key = playlist.id,
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
                            modifier = Modifier.clickable {
                                backStack.add(Route.PlaylistDetail(playlist.id))
                            },
                            elevation = ListItemDefaults.elevation(elevation = elevation),
                        )
                    }
                }
            }
    }
}
