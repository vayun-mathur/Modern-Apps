package com.vayunmathur.youpipe.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvTextField
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.PlaylistItem
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/**
 * TV add-to-playlist: staged membership as a focusable list with a confirm dialog.
 *
 * The phone stages membership in an `AddToListDialog` and applies on OK. Same staging
 * here — each playlist row toggles membership on CENTER — and OK/Cancel live in the
 * header. The new-playlist field is a [TvTextField] (IME only on CENTER). BACK pops
 * without applying.
 */
@Composable
fun TvAddToPlaylistPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    videoID: Long,
    includeWatchLater: Boolean = true,
) {
    val allPlaylists by ypvm.playlists.collectAsStateWithLifecycle()
    val playlists = if (includeWatchLater) allPlaylists else allPlaylists.filter { !it.mandatory }
    val allItems by ypvm.allPlaylistItems.collectAsStateWithLifecycle()
    val videoState by ypvm.videoState.collectAsStateWithLifecycle()
    val closeFocus = remember { FocusRequester() }

    val data = videoState.data
    if (data == null) {
        // Same fallback as the phone dialog: the video state went away (rotation, process
        // death) so there is nothing to add — back out instead of staging against nothing.
        TvAddToPlaylistCloseHeader(closeFocus = closeFocus, onClose = { backStack.pop() })
        return
    }

    val video = VideoInfo(
        data.title, videoID, data.duration, data.views, data.uploadDate, data.thumbnailURL, data.author,
    )
    val membership = remember(allItems, videoID) {
        allItems.filter { it.videoItem.videoID == videoID }.map { it.playlistId }.toSet()
    }
    var staged by remember(membership) { mutableStateOf(membership) }
    var newName by remember { mutableStateOf("") }
    val watchLaterLabel = stringResource(R.string.playlist_watch_later)

    TvInitialFocus(closeFocus)
    TvSettingsBody {
        Column(Modifier.fillMaxSize()) {
            TvAddToPlaylistHeader(
                onConfirm = {
                    applyStagedMembership(playlists, membership, staged, allItems, video, videoID, ypvm)
                    backStack.pop()
                },
                onCancel = { backStack.pop() },
            )
            TvAddToPlaylistList(
                playlists = playlists, allPlaylists = allPlaylists, staged = staged,
                watchLaterLabel = watchLaterLabel, newName = newName,
                onNewNameChange = { newName = it },
                onToggle = { playlist, checked ->
                    staged = if (checked) staged - playlist.id else staged + playlist.id
                },
                onCreate = {
                    ypvm.playlistOps.createPlaylistAndAddVideo(newName.trim(), video)
                    backStack.pop()
                },
            )
        }
    }
}

private fun applyStagedMembership(
    playlists: List<Playlist>,
    membership: Set<Long>,
    staged: Set<Long>,
    allItems: List<PlaylistItem>,
    video: VideoInfo,
    videoID: Long,
    ypvm: YouPipeViewModel,
) {
    playlists.forEach { playlist ->
        val wasMember = playlist.id in membership
        val nowMember = playlist.id in staged
        if (nowMember && !wasMember) {
            ypvm.playlistOps.addVideoToPlaylist(playlist.id, video)
        } else if (!nowMember && wasMember) {
            allItems.firstOrNull {
                it.playlistId == playlist.id && it.videoItem.videoID == videoID
            }?.let { ypvm.playlistOps.removeFromPlaylist(it) }
        }
    }
}

@Composable
private fun TvAddToPlaylistCloseHeader(closeFocus: FocusRequester, onClose: () -> Unit) {
    TvInitialFocus(closeFocus)
    TvScreenHeader(
        title = stringResource(R.string.add_to_playlist),
        actions = {
            TvButton(
                text = stringResource(R.string.action_close),
                onClick = onClose,
            )
        },
    )
}

@Composable
private fun TvAddToPlaylistHeader(onConfirm: () -> Unit, onCancel: () -> Unit) {
    TvScreenHeader(
        title = stringResource(R.string.add_to_playlist),
        actions = {
            TvButton(
                text = stringResource(UiR.string.ok),
                onClick = onConfirm,
                primary = true,
            )
            TvButton(
                text = stringResource(UiR.string.cancel),
                onClick = onCancel,
            )
        },
    )
}

@Composable
private fun TvAddToPlaylistList(
    playlists: List<Playlist>,
    allPlaylists: List<Playlist>,
    staged: Set<Long>,
    watchLaterLabel: String,
    newName: String,
    onNewNameChange: (String) -> Unit,
    onToggle: (Playlist, Boolean) -> Unit,
    onCreate: () -> Unit,
) {
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = tvContentPadding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(playlists, key = { it.id }) { playlist ->
            val checked = playlist.id in staged
            TvListRow(
                headline = if (playlist.mandatory) watchLaterLabel else playlist.name,
                trailing = if (checked) {
                    { IconCheck() }
                } else {
                    null
                },
                selected = checked,
                onClick = { onToggle(playlist, checked) },
            )
        }
        item(key = "new-playlist") {
            TvNewPlaylistRow(
                newName = newName,
                canCreate = newName.isNotBlank() &&
                    allPlaylists.none { it.name == newName.trim() },
                onNewNameChange = onNewNameChange,
                onCreate = onCreate,
            )
        }
    }
}

@Composable
private fun TvNewPlaylistRow(
    newName: String,
    canCreate: Boolean,
    onNewNameChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    var nameOpen by remember { mutableStateOf(false) }
    if (!nameOpen) {
        TvListRow(
            headline = stringResource(R.string.new_playlist),
            onClick = { nameOpen = true },
        )
    } else {
        TvTextField(
            value = newName,
            onValueChange = onNewNameChange,
            placeholder = stringResource(R.string.label_playlist_name),
            onSubmit = {
                if (canCreate) {
                    onCreate()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TvButton(
            text = stringResource(R.string.new_playlist),
            onClick = onCreate,
            enabled = canCreate,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
