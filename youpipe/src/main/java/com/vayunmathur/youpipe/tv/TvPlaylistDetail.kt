package com.vayunmathur.youpipe.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.PlaylistItem
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV playlist detail: header (title, count, play-all, delete) + a 4-column grid of
 * thumbnails with D-pad multiselect for removal.
 *
 * The phone combines long-press selection with drag-handle reorder. Neither exists on a
 * TV: CENTER opens, long-press toggles selection as a fallback, and removal is a header
 * button. Reorder stays phone-only (same rationale as [TvSavedPage]).
 */
@Composable
fun TvPlaylistDetailPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    playlistId: Long,
) {
    val playlist by remember(playlistId) { ypvm.playlistById(playlistId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val itemsFlow = remember(playlistId) { ypvm.playlistItemsFor(playlistId) }
    val playlistItems by itemsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val history by ypvm.historyVideos.collectAsState()
    val historyById = remember(history) { history.associate { it.id to it.progress } }
    val mandatory = playlist?.mandatory == true
    val title = when {
        mandatory -> stringResource(R.string.playlist_watch_later)
        else -> playlist?.name.orEmpty()
    }
    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }
    BackHandler(enabled = isSelectionMode) { selectedIds.clear() }
    var confirmDelete by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus)
    val rows = rememberTvVideoRows(
        videos = remember(playlistItems) { playlistItems.map { it.videoItem } },
        ypvm = ypvm,
        historyProgressMsById = historyById,
    )
    TvPlaylistDetailBody(
        backStack = backStack,
        ypvm = ypvm,
        playlist = playlist,
        playlistItems = playlistItems,
        rows = rows,
        title = title,
        mandatory = mandatory,
        selectedIds = selectedIds,
        isSelectionMode = isSelectionMode,
        onDeletePlaylist = { confirmDelete = true },
    )
    TvPlaylistDeleteDialog(
        playlist = playlist,
        title = title,
        confirmDelete = confirmDelete,
        ypvm = ypvm,
        backStack = backStack,
        onDismiss = { confirmDelete = false },
    )
}

@Composable
private fun TvPlaylistDetailBody(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    playlist: Playlist?,
    playlistItems: List<PlaylistItem>,
    rows: List<TvVideoRow>,
    title: String,
    mandatory: Boolean,
    selectedIds: SnapshotStateList<Long>,
    isSelectionMode: Boolean,
    onDeletePlaylist: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TvPlaylistDetailHeader(
            backStack = backStack,
            ypvm = ypvm,
            playlist = playlist,
            playlistItems = playlistItems,
            title = title,
            mandatory = mandatory,
            selectedIds = selectedIds,
            isSelectionMode = isSelectionMode,
            onDeletePlaylist = onDeletePlaylist,
        )
        if (rows.isEmpty()) {
            TvEmptyState(title = stringResource(R.string.playlist_empty))
            return@Column
        }
        TvPlaylistDetailGrid(
            backStack = backStack,
            playlistItems = playlistItems,
            rows = rows,
            selectedIds = selectedIds,
            isSelectionMode = isSelectionMode,
            modifier = Modifier.fillMaxSize().weight(1f),
        )
    }
}

@Composable
private fun TvPlaylistDetailHeader(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    playlist: Playlist?,
    playlistItems: List<PlaylistItem>,
    title: String,
    mandatory: Boolean,
    selectedIds: SnapshotStateList<Long>,
    isSelectionMode: Boolean,
    onDeletePlaylist: () -> Unit,
) {
    TvScreenHeader(
        title = if (isSelectionMode) {
            stringResource(R.string.selected_count, selectedIds.size.toString())
        } else {
            title
        },
        subtitle = stringResource(R.string.playlist_video_count, playlistItems.size),
        actions = {
            TvPlaylistHeaderActions(
                backStack = backStack,
                ypvm = ypvm,
                playlist = playlist,
                playlistItems = playlistItems,
                mandatory = mandatory,
                selectedIds = selectedIds,
                isSelectionMode = isSelectionMode,
                onDeletePlaylist = onDeletePlaylist,
            )
        },
    )
}

@Composable
private fun TvPlaylistHeaderActions(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    playlist: Playlist?,
    playlistItems: List<PlaylistItem>,
    mandatory: Boolean,
    selectedIds: SnapshotStateList<Long>,
    isSelectionMode: Boolean,
    onDeletePlaylist: () -> Unit,
) {
    if (isSelectionMode) {
        TvButton(
            text = stringResource(UiR.string.delete),
            onClick = {
                playlistItems.filter { it.id in selectedIds }
                    .forEach { ypvm.playlistOps.removeFromPlaylist(it) }
                selectedIds.clear()
            },
            icon = { IconDelete() },
        )
        TvButton(
            text = stringResource(UiR.string.cancel),
            onClick = { selectedIds.clear() },
        )
    } else {
        TvPlaylistNormalActions(backStack, playlist, playlistItems, mandatory, onDeletePlaylist)
    }
}

@Composable
private fun TvPlaylistNormalActions(
    backStack: NavBackStack<TvRoute>,
    playlist: Playlist?,
    playlistItems: List<PlaylistItem>,
    mandatory: Boolean,
    onDeletePlaylist: () -> Unit,
) {
    if (playlistItems.isNotEmpty()) {
        TvButton(
            text = stringResource(UiR.string.play),
            onClick = {
                backStack.playVideo(playlistItems.first().videoItem.videoID)
            },
            icon = { IconPlay() },
            primary = true,
        )
    }
    if (!mandatory && playlist != null) {
        TvButton(
            text = stringResource(R.string.action_delete_playlist),
            onClick = onDeletePlaylist,
            icon = { IconDelete() },
        )
    }
}

@Composable
private fun TvPlaylistDetailGrid(
    backStack: NavBackStack<TvRoute>,
    playlistItems: List<PlaylistItem>,
    rows: List<TvVideoRow>,
    selectedIds: SnapshotStateList<Long>,
    isSelectionMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val rowByVideoId = remember(rows) { rows.associateBy { it.row.videoID } }
    TvGrid(columns = TV_GRID_COLUMNS, modifier = modifier) {
        items(playlistItems.size, key = { playlistItems[it].id }) { index ->
            val item = playlistItems[index]
            val row = rowByVideoId[item.videoItem.videoID]
            if (row != null) {
                val isSelected = item.id in selectedIds
                TvSelectableCard(
                    item = row,
                    selected = isSelected,
                    inSelectionMode = isSelectionMode,
                    onOpen = { backStack.playVideo(item.videoItem.videoID) },
                    onToggleSelect = {
                        if (isSelected) selectedIds.remove(item.id) else selectedIds.add(item.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun TvPlaylistDeleteDialog(
    playlist: Playlist?,
    title: String,
    confirmDelete: Boolean,
    ypvm: YouPipeViewModel,
    backStack: NavBackStack<TvRoute>,
    onDismiss: () -> Unit,
) {
    if (!confirmDelete) return
    TvDialog(
        title = stringResource(R.string.action_delete_playlist),
        onDismissRequest = onDismiss,
        body = title,
        buttons = {
            TvButton(
                text = stringResource(UiR.string.delete),
                onClick = {
                    playlist?.let { ypvm.playlistOps.deletePlaylist(it) }
                    onDismiss()
                    backStack.pop()
                },
                primary = true,
            )
            TvButton(
                text = stringResource(UiR.string.cancel),
                onClick = onDismiss,
            )
        },
    )
}
