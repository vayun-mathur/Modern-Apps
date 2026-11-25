package com.vayunmathur.music.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Chip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.domain.WearPageId
import com.vayunmathur.music.wear.domain.WearPageKey

/** Albums tab, mirroring the phone's Albums page ordering. */
@Composable
fun WearAlbumListScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val page = WearPageId(WearPageKey.ALBUMS)
    WearPageLoader(store, page)
    val pages by store.pages.collectAsState()
    Column {
        Text("Albums")
        LazyColumn {
            items(pages[page]?.albums.orEmpty()) { album ->
                Chip(label = { Text(album.name + " — " + album.artistName) }, onClick = { onRoute(WearRoute.AlbumDetail(album.id)) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
