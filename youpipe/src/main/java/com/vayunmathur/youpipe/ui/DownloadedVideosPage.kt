package com.vayunmathur.youpipe.ui
import com.vayunmathur.youpipe.R

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.adaptiveGridCells
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isExpandedWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.DownloadManager
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadedVideosPage(backStack: NavBackStack<Route>, youPipeViewModel: YouPipeViewModel) {
    val downloadedVideos by youPipeViewModel.downloadedVideos.collectAsState()
    val activeDownloads by DownloadManager.activeDownloads.collectAsState()
    val downloads by youPipeViewModel.downloadedVideosByRecency.collectAsState()

    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedActiveIds by remember { mutableStateOf(setOf<Long>()) }
    val isSelectionMode = selectedIds.isNotEmpty() || selectedActiveIds.isNotEmpty()

    val context = androidx.compose.ui.platform.LocalContext.current
    if (isExpandedWidth() && !isSelectionMode) {
        // Desktop: completed downloads fill the window with an adaptive grid instead
        // of stretching one column across it. Active downloads and selection mode
        // keep the compact list below.
        DownloadedGrid(
            backStack = backStack,
            youPipeViewModel = youPipeViewModel,
            downloads = downloads,
        )
        return
    }
    LazyListScaffold(
        topBar = {
            TopAppBar(
                title = { 
                    if (isSelectionMode) {
                        val totalSelected = selectedIds.size + selectedActiveIds.size
                        Text(stringResource(R.string.selected_count, totalSelected))
                    } else {
                        Text(stringResource(R.string.title_downloads))
                    }
                },
                navigationIcon = {
                    if (!isSelectionMode) {
                        IconNavigation(backStack)
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            selectedIds.forEach { id ->
                                downloadedVideos.find { it.id == id }?.let { youPipeViewModel.deleteDownloadedVideo(it) }
                            }
                            selectedActiveIds.forEach { id ->
                                DownloadManager.cancelDownload(context, id)
                            }
                            selectedIds = emptySet()
                            selectedActiveIds = emptySet()
                        }) {
                            IconDelete()
                        }
                    }
                },
            )
        },
    ) {
            // Active downloads
            items(activeDownloads.toList(), key = { (videoID, _) -> "active-$videoID" }) { (videoID, status) ->
                val isSelected = videoID in selectedActiveIds
                val itemModifier = Modifier.combinedClickable(
                    onClick = {
                        if (isSelectionMode) {
                            selectedActiveIds = if (isSelected) selectedActiveIds - videoID else selectedActiveIds + videoID
                        }
                    },
                    onLongClick = {
                        if (!isSelectionMode) {
                            selectedActiveIds = setOf(videoID)
                        }
                    }
                )

                Row(
                    modifier = itemModifier,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelectionMode) {
                        SelectionIndicator(isSelected)
                    }
                    ListItem(
                        modifier = Modifier.weight(1f),
                        leadingContent = {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(status.videoInfo.thumbnailURL)
                                    .memoryCacheKey("dl-thumb-$videoID")
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        },
                        content = { Text(status.videoInfo.name, maxLines = 1) },
                        supportingContent = { Text("${(status.progress * 100).toInt()}%") },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    progress = { status.progress.toFloat() },
                                    modifier = Modifier.size(24.dp)
                                )
                                if (!isSelectionMode) {
                                    IconButton(onClick = { DownloadManager.cancelDownload(context, videoID) }) {
                                        IconClose()
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Completed downloads
            items(downloads, key = { it.id }) { downloadItem ->
                val isSelected = downloadItem.id in selectedIds
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelectionMode) {
                        SelectionIndicator(isSelected)
                    }
                    VideoItem(
                        backStack = backStack,
                        youPipeViewModel = youPipeViewModel,
                        videoInfo = downloadItem.videoItem,
                        showAuthor = true,
                        modifier = Modifier.weight(1f).combinedClickable(
                            onClick = {
                                if (isSelectionMode) {
                                    selectedIds = if (isSelected) selectedIds - downloadItem.id else selectedIds + downloadItem.id
                                } else {
                                    backStack.add(Route.VideoPage(downloadItem.id))
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    selectedIds = setOf(downloadItem.id)
                                }
                            }
                        ),
                        onClick = null,
                        backupOnClick = false,
                        // `id` is the video id, and is what the tap above navigates with.
                        titleSharedKey = "youpipe-video-title-${downloadItem.id}",
                    )
                }
            }
    }
}

// RAW SCAFFOLD EXCEPTION: an adaptive video grid (LazyVerticalGrid of adaptiveGridCells)
// under the downloads top bar. No shared scaffold hosts a grid body; selection mode and
// active downloads keep the compact LazyListScaffold list in DownloadedVideosPage.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DownloadedGrid(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
    downloads: List<com.vayunmathur.youpipe.data.DownloadedVideo>,
) {
    com.vayunmathur.library.ui.Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_downloads)) },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = adaptiveGridCells(320.dp),
            modifier = Modifier.padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(downloads, key = { it.id }) { downloadItem ->
                VideoItem(
                    backStack = backStack,
                    youPipeViewModel = youPipeViewModel,
                    videoInfo = downloadItem.videoItem,
                    showAuthor = true,
                    modifier = Modifier.combinedClickable(
                        onClick = { backStack.add(Route.VideoPage(downloadItem.id)) },
                        // Long-press is a no-op here: multiselect lives in the compact list.
                        onLongClick = {},
                    ),
                    onClick = null,
                    backupOnClick = false,
                    // No shared-element key: the compact list composes the same videos and
                    // one key needs one origin (see VideoRow's titleSharedKey note).
                    vertical = true,
                )
            }
        }
    }
}
