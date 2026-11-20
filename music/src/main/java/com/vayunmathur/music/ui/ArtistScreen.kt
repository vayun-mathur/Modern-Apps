package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.music.ui.components.ShufflePlayFab
import com.vayunmathur.music.platform.AlbumArt
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Artist

@Composable
fun ArtistsTabContent(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel) {
    val artists by musicViewModel.artists.collectAsState()
    val loaded by musicViewModel.loaded.collectAsState()
    if (artists.isEmpty() && loaded) {
        EmptyState(title = stringResource(R.string.empty_artists_title), message = stringResource(R.string.empty_artists_message))
        return
    }
    ListPage<Artist, Route, Route.Song>(backStack, artists, stringResource(R.string.app_name), {
        Text(
            it.name.ifBlank { stringResource(R.string.unknown_artist) },
            modifier = Modifier.sharedText("music-artist-name-${it.id}")
        )
    }, {
    }, {
        Route.ArtistDetail(it)
    }, leadingContent = { artist ->
        val albumIds by musicViewModel.matchedAlbumsForArtist(artist.id)
        val allAlbums by musicViewModel.albums.collectAsState()
        val albumsUris by remember { derivedStateOf { allAlbums.filter { it.id in albumIds }.map { it.uri.toUri() } } }
        AlbumArt(albumsUris, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).sharedContainer("music-artist-art-${artist.id}"))
    }, searchEnabled = true, fab = {
        ShufflePlayFab(musicViewModel)
    }, searchString = { it.name }, loading = !loaded,
    // getArtists already returns them sorted by name.
    )
}
