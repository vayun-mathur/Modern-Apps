package com.vayunmathur.youpipe.platform

import androidx.lifecycle.viewModelScope
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.PlaylistItem
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Playlist mutations for [YouPipeViewModel].
 *
 * Split out: TooManyFunctions cap (25/class). Same module and package, so
 * internal access is unchanged; behavior identical.
 */
internal class YouPipePlaylistOps(private val vm: YouPipeViewModel) {
    private val repository get() = vm.repository
    private val viewModelScope get() = vm.viewModelScope

    fun createPlaylist(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val maxPosition = repository.playlistStore.getAllPlaylists().maxOfOrNull { it.position } ?: 0.0
            repository.playlistStore.upsertPlaylist(Playlist(name = name, position = maxPosition + 1))
        }
    }

    /** Deletes a user playlist. Mandatory playlists (Watch later) can never be removed. */
    fun deletePlaylist(playlist: Playlist) {
        if (playlist.mandatory) return
        viewModelScope.launch(Dispatchers.IO) { repository.playlistStore.deletePlaylist(playlist) }
    }

    fun reorderPlaylists(list: List<Playlist>) {
        viewModelScope.launch(Dispatchers.IO) { repository.playlistStore.upsertPlaylists(list) }
    }

    /** Adds [video] to [playlistId], deduped by videoID; a no-op if already present. */
    fun addVideoToPlaylist(playlistId: Long, video: VideoInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.playlistStore.getPlaylistItemsForPlaylist(playlistId)
            if (existing.any { it.videoItem.videoID == video.videoID }) return@launch
            val maxPosition = existing.maxOfOrNull { it.position } ?: 0.0
            repository.playlistStore.upsertPlaylistItem(
                PlaylistItem(
                    playlistId = playlistId,
                    videoItem = video,
                    position = maxPosition + 1,
                    timestamp = Clock.System.now(),
                ),
            )
        }
    }

    fun removeFromPlaylist(item: PlaylistItem) {
        viewModelScope.launch(Dispatchers.IO) { repository.playlistStore.deletePlaylistItem(item) }
    }

    /** Creates a playlist and immediately adds [video] to it (the dialog's "New playlist" option). */
    fun createPlaylistAndAddVideo(name: String, video: VideoInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val maxPosition = repository.playlistStore.getAllPlaylists().maxOfOrNull { it.position } ?: 0.0
            val id = repository.playlistStore.upsertPlaylist(Playlist(name = name, position = maxPosition + 1))
            repository.playlistStore.upsertPlaylistItem(
                PlaylistItem(
                    playlistId = id,
                    videoItem = video,
                    position = 1.0,
                    timestamp = Clock.System.now(),
                ),
            )
        }
    }

    fun reorderPlaylistItems(list: List<PlaylistItem>) {
        viewModelScope.launch(Dispatchers.IO) { repository.playlistStore.upsertPlaylistItems(list) }
    }
}
