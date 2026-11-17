package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.ui.components.PlayingBottomBar
import com.vayunmathur.music.platform.AlbumDetailUiState
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.formatDuration

/** Binds [AlbumDetailContent] to the ViewModel. */
@Composable
fun AlbumDetailScreen(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel, albumId: Long) {
    val albumValue by musicViewModel.albumState(albumId)
    val album = albumValue ?: return
    val allMusic by musicViewModel.music.collectAsState()
    val musicInAlbum = remember(allMusic, albumId) {
        allMusic.filter { it.albumId == albumId }
            .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))
    }
    val totalDurationMs = remember(musicInAlbum) {
        musicInAlbum.sumOf { it.duration }
    }

    val albumYear = remember(musicInAlbum) {
        val extractedYear = musicInAlbum.firstOrNull()?.year ?: 0
        if (extractedYear > 0) extractedYear.toString() else "Unknown Year"
    }

    val artistIds by musicViewModel.matchedArtistsForAlbum(albumId)

    AlbumDetailContent(
        state = AlbumDetailUiState(
            albumId = albumId,
            name = album.name,
            artUri = album.uri.toUri(),
            artistName = album.artistString(musicViewModel),
            // Only a single-artist album has an unambiguous page to open.
            artistId = artistIds.singleOrNull(),
            // Artist is rendered separately (as a tappable link), so it's passed empty here
            // and the format's leading artist line is trimmed off.
            info = stringResource(
                R.string.album_info_format,
                "",
                albumYear,
                pluralStringResource(R.plurals.num_songs_format, musicInAlbum.size, musicInAlbum.size),
                formatDuration(totalDurationMs),
            ).trimStart(),
            tracks = musicInAlbum,
            playingSongId = musicViewModel.playingSongIdFrom("album_$albumId"),
        ),
        actions = musicViewModel,
        backStack = backStack,
        bottomBar = { PlayingBottomBar(musicViewModel, backStack, Modifier.navigationBarsPadding()) },
    )
}
