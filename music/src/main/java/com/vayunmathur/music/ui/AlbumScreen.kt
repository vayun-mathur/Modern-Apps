package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.music.ui.components.ShufflePlayFab
import com.vayunmathur.music.platform.AlbumArt
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Album

@Composable
fun AlbumsTabContent(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel) {
    val albums by musicViewModel.albums.collectAsState()
    ListPage<Album, Route, Route.Song>(backStack, albums, stringResource(R.string.app_name), {
        Text(it.name, modifier = Modifier.sharedText("music-album-title-${it.id}"))
    }, {
        Text(it.artistString(musicViewModel), modifier = Modifier.sharedText("music-album-artist-${it.id}"))
    }, {
        Route.AlbumDetail(it)
    }, leadingContent = { album ->
        AlbumArt(album.uri.toUri(), Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).sharedContainer("music-album-art-${album.id}"))
    }, searchEnabled = true, fab = {
        ShufflePlayFab(musicViewModel)
    }, searchString = { it.name },
    // getAlbums already returns them sorted by name.
    )
}
