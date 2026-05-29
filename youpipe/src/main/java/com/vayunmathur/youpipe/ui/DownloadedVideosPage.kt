package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.util.DownloadManager
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadedVideosPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val downloadedVideos by viewModel.data<DownloadedVideo>().collectAsState()
    val activeDownloads by DownloadManager.activeDownloads.collectAsState()
    val downloads = downloadedVideos.sortedByDescending { it.timestamp }

    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedActiveIds by remember { mutableStateOf(setOf<Long>()) }
    val isSelectionMode = selectedIds.isNotEmpty() || selectedActiveIds.isNotEmpty()

    val context = androidx.compose.ui.platform.LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    val totalSelected = selectedIds.size + selectedActiveIds.size
                    Text(if (isSelectionMode) "$totalSelected Selected" else "Downloads") 
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            selectedIds.forEach { id ->
                                downloadedVideos.find { it.id == id }?.let { viewModel.delete(it) }
                            }
                            selectedActiveIds.forEach { id ->
                                DownloadManager.cancelDownload(context, id)
                            }
                            selectedIds = emptySet()
                            selectedActiveIds = emptySet()
                        }) {
                            Icon(painterResource(com.vayunmathur.library.R.drawable.delete_24px), contentDescription = "Delete selected")
                        }
                    }
                }
            )
        },
        bottomBar = { 
            if (!isSelectionMode) {
                BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.Downloads) 
            }
        }
    ) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
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
                        headlineContent = { Text(status.videoInfo.name, maxLines = 1) },
                        supportingContent = { Text("${(status.progress * 100).toInt()}%") },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    progress = { status.progress.toFloat() },
                                    modifier = Modifier.size(24.dp)
                                )
                                if (!isSelectionMode) {
                                    IconButton(onClick = { DownloadManager.cancelDownload(context, videoID) }) {
                                        Icon(
                                            painterResource(com.vayunmathur.library.R.drawable.close_24px),
                                            contentDescription = "Cancel"
                                        )
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
                        viewModel = viewModel,
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
                        backupOnClick = false
                    )
                }
            }
        }
    }
}

@Composable
fun SelectionIndicator(isSelected: Boolean) {
    Box(
        modifier = Modifier
            .padding(start = 16.dp)
            .size(24.dp)
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                painter = painterResource(com.vayunmathur.library.R.drawable.outline_check_24),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
