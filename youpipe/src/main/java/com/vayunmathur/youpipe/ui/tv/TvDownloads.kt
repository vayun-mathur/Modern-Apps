package com.vayunmathur.youpipe.ui.tv

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInfoCard
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.tv.tvGridHeader
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.platform.DownloadManager
import com.vayunmathur.youpipe.platform.DownloadStatus
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/**
 * TV downloads: active downloads as full-width [TvInfoCard]s with progress, completed
 * downloads as a 4-column grid of thumbnails, with D-pad multiselect. Mirrors the phone
 * `DownloadedVideosPage` binding (activeDownloads + downloadedVideosByRecency).
 * Selection deletes; BACK clears first.
 */
@Composable
fun TvDownloadsPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val downloadedVideos by ypvm.downloadedVideos.collectAsState()
    val hasLoaded by ypvm.hasLoadedDownloads.collectAsState()
    val activeDownloads by DownloadManager.activeDownloads.collectAsState()
    val downloads by ypvm.downloadedVideosByRecency.collectAsState()
    val historyById = remember(downloadedVideos) { emptyMap<Long, Long>() }
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }
    val activeTitle = stringResource(R.string.download)
    val doneTitle = stringResource(R.string.title_downloads)

    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedActiveIds by remember { mutableStateOf(setOf<Long>()) }
    val isSelectionMode = selectedIds.isNotEmpty() || selectedActiveIds.isNotEmpty()
    BackHandler(enabled = isSelectionMode) {
        selectedIds = emptySet()
        selectedActiveIds = emptySet()
    }

    val rows = rememberTvVideoRows(
        videos = remember(downloads) { downloads.map { it.videoItem } },
        ypvm = ypvm,
        historyProgressMsById = historyById,
    )

    TvInitialFocus(firstFocus)
    Column(Modifier.fillMaxSize()) {
        TvDownloadsContent(
            hasLoaded = hasLoaded,
            modifier = Modifier.weight(1f),
            activeDownloads = activeDownloads, rows = rows,
            activeTitle = activeTitle, doneTitle = doneTitle,
            selectedIds = selectedIds, selectedActiveIds = selectedActiveIds,
            isSelectionMode = isSelectionMode,
            onDelete = {
                deleteSelectedDownloads(downloadedVideos, selectedIds, selectedActiveIds, context, ypvm)
                selectedIds = emptySet()
                selectedActiveIds = emptySet()
            },
            onCancelSelection = { selectedIds = emptySet(); selectedActiveIds = emptySet() },
            onToggleActive = { videoID, isSelected ->
                selectedActiveIds = if (isSelected) selectedActiveIds - videoID else selectedActiveIds + videoID
            },
            onToggleDone = { id, isSelected ->
                selectedIds = if (isSelected) selectedIds - id else selectedIds + id
            },
            onOpen = { backStack.playVideo(it) },
        )
    }
}

private fun deleteSelectedDownloads(
    downloadedVideos: List<DownloadedVideo>,
    selectedIds: Set<Long>,
    selectedActiveIds: Set<Long>,
    context: Context,
    ypvm: YouPipeViewModel,
) {
    selectedIds.forEach { id ->
        downloadedVideos.find { it.id == id }?.let { ypvm.deleteDownloadedVideo(it) }
    }
    selectedActiveIds.forEach { id -> DownloadManager.cancelDownload(context, id) }
}

@Composable
private fun TvDownloadsContent(
    hasLoaded: Boolean = true,
    modifier: Modifier = Modifier,
    activeDownloads: Map<Long, DownloadStatus>,
    rows: List<TvVideoRow>,
    activeTitle: String,
    doneTitle: String,
    selectedIds: Set<Long>,
    selectedActiveIds: Set<Long>,
    isSelectionMode: Boolean,
    onDelete: () -> Unit,
    onCancelSelection: () -> Unit,
    onToggleActive: (Long, Boolean) -> Unit,
    onToggleDone: (Long, Boolean) -> Unit,
    onOpen: (Long) -> Unit,
) {
    TvDownloadsHeader(
        isSelectionMode = isSelectionMode,
        selectedCount = selectedIds.size + selectedActiveIds.size,
        onDelete = onDelete,
        onCancelSelection = onCancelSelection,
    )
    val activeList = remember(activeDownloads) { activeDownloads.toList() }
    if (!hasLoaded && rows.isEmpty() && activeList.isEmpty()) {
        TvLoading()
        return
    }
    if (rows.isEmpty() && activeList.isEmpty()) {
        TvEmptyState(title = stringResource(R.string.playlist_empty))
        return
    }
    TvDownloadsGrid(
        modifier = modifier, activeList = activeList, rows = rows,
        activeTitle = activeTitle, doneTitle = doneTitle,
        selectedIds = selectedIds, selectedActiveIds = selectedActiveIds,
        isSelectionMode = isSelectionMode, onToggleActive = onToggleActive,
        onToggleDone = onToggleDone, onOpen = onOpen,
    )
}

@Composable
private fun TvDownloadsHeader(
    isSelectionMode: Boolean,
    selectedCount: Int,
    onDelete: () -> Unit,
    onCancelSelection: () -> Unit,
) {
    TvScreenHeader(
        title = if (isSelectionMode) {
            stringResource(R.string.selected_count, selectedCount.toString())
        } else {
            stringResource(R.string.title_downloads)
        },
        actions = {
            if (isSelectionMode) {
                TvButton(
                    text = stringResource(UiR.string.delete),
                    onClick = onDelete,
                    icon = { IconDelete() },
                )
                TvButton(
                    text = stringResource(UiR.string.cancel),
                    onClick = onCancelSelection,
                    icon = { IconClose() },
                )
            }
        },
    )
}

@Composable
private fun TvDownloadsGrid(
    modifier: Modifier = Modifier,
    activeList: List<Pair<Long, DownloadStatus>>,
    rows: List<TvVideoRow>,
    activeTitle: String,
    doneTitle: String,
    selectedIds: Set<Long>,
    selectedActiveIds: Set<Long>,
    isSelectionMode: Boolean,
    onToggleActive: (Long, Boolean) -> Unit,
    onToggleDone: (Long, Boolean) -> Unit,
    onOpen: (Long) -> Unit,
) {
    TvGrid(columns = TV_GRID_COLUMNS, modifier = Modifier.fillMaxSize().then(modifier)) {
        if (activeList.isNotEmpty()) {
            tvGridHeader(
                key = "active-header",
                title = activeTitle,
                first = true,
            )
            items(activeList.size, key = { index -> "active-${activeList[index].first}" }) { index ->
                val (videoID, status) = activeList[index]
                val isSelected = videoID in selectedActiveIds
                TvActiveDownloadCard(
                    videoID = videoID,
                    status = status,
                    isSelected = isSelected,
                    isSelectionMode = isSelectionMode,
                    onToggle = { onToggleActive(videoID, isSelected) },
                )
            }
        }
        if (rows.isNotEmpty()) {
            tvGridHeader(
                key = "done-header",
                title = doneTitle,
                first = activeList.isEmpty(),
            )
            items(rows.size, key = { rows[it].row.videoID }) { index ->
                val item = rows[index]
                val isSelected = item.info.videoID in selectedIds
                TvSelectableCard(
                    item = item,
                    selected = isSelected,
                    inSelectionMode = isSelectionMode,
                    onOpen = { onOpen(item.row.videoID) },
                    onToggleSelect = { onToggleDone(item.info.videoID, isSelected) },
                )
            }
        }
    }
}

@Composable
private fun TvActiveDownloadCard(
    videoID: Long,
    status: DownloadStatus,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    TvInfoCard(
        title = status.videoInfo.name,
        onClick = { if (isSelectionMode) onToggle() },
        subtitle = "${(status.progress * 100).toInt()}%" +
            if (isSelected) "  •  ✓" else "",
        leading = {
            if (status.videoInfo.thumbnailURL.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(status.videoInfo.thumbnailURL)
                        .memoryCacheKey("dl-thumb-$videoID")
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        },
        leadingSize = 96.dp,
        progress = status.progress.toFloat(),
        onLongClick = onToggle,
    )
}
