package com.vayunmathur.youpipe.ui.dialogs

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AddToListDialog
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/**
 * Add-to-playlist dialog opened from the video page. Lists Watch later + the user's playlists
 * (Downloads is not a playlist and is excluded), each with a membership checkbox. A "New
 * playlist" field creates a playlist and adds the current video in one step. Membership is
 * staged and applied on OK.
 */
@Composable
fun AddToPlaylist(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
    videoID: Long,
    includeWatchLater: Boolean = true,
) {
    val allPlaylists by youPipeViewModel.playlists.collectAsStateWithLifecycle()
    val playlists = if (includeWatchLater) allPlaylists else allPlaylists.filter { !it.mandatory }
    val allItems by youPipeViewModel.allPlaylistItems.collectAsStateWithLifecycle()
    val videoState by youPipeViewModel.videoState.collectAsStateWithLifecycle()

    // We just navigated from this video, so its loaded state is the source for the stored row.
    val data = videoState.data
    if (data == null) {
        Dialog({ backStack.pop() }) {
            Card { Text(stringResource(R.string.add_to_playlist), Modifier.padding(16.dp)) }
        }
        return
    }

    val video = VideoInfo(
        data.title, videoID, data.duration, data.views, data.uploadDate, data.thumbnailURL, data.author,
    )

    // playlistIds this video already belongs to, for the checkbox states.
    val membership = remember(allItems, videoID) {
        allItems.filter { it.videoItem.videoID == videoID }.map { it.playlistId }.toSet()
    }

    val watchLaterLabel = stringResource(R.string.playlist_watch_later)
    AddToListDialog(
        title = stringResource(R.string.add_to_playlist),
        options = playlists,
        itemLabel = { playlist ->
            if (playlist.mandatory) watchLaterLabel else playlist.name
        },
        confirmLabel = stringResource(android.R.string.ok),
        dismissLabel = stringResource(android.R.string.cancel),
        itemKey = { it.id },
        initiallyChecked = { it.id in membership },
        createLabel = stringResource(R.string.new_playlist),
        canCreate = { name -> name.isNotBlank() && allPlaylists.none { it.name == name.trim() } },
        onCreate = { name -> youPipeViewModel.playlistOps.createPlaylistAndAddVideo(name.trim(), video) },
        onConfirm = { selected ->
            val selectedIds = selected.map { it.id }.toSet()
            playlists.forEach { playlist ->
                val wasMember = playlist.id in membership
                val nowMember = playlist.id in selectedIds
                if (nowMember && !wasMember) {
                    youPipeViewModel.playlistOps.addVideoToPlaylist(playlist.id, video)
                } else if (!nowMember && wasMember) {
                    allItems.firstOrNull {
                        it.playlistId == playlist.id && it.videoItem.videoID == videoID
                    }?.let { youPipeViewModel.playlistOps.removeFromPlaylist(it) }
                }
            }
            backStack.pop()
        },
        onDismiss = { backStack.pop() },
    )
}
