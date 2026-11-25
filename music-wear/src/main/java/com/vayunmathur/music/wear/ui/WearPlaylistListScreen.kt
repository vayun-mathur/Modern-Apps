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

/** Playlists tab, mirroring the phone's Playlists page ordering. */
@Composable
fun WearPlaylistListScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val page = WearPageId(WearPageKey.PLAYLISTS)
    WearPageLoader(store, page)
    val pages by store.pages.collectAsState()
    Column {
        Text("Playlists")
        LazyColumn {
            items(pages[page]?.playlists.orEmpty()) { playlist ->
                Chip(label = { Text(playlist.name) }, onClick = { onRoute(WearRoute.PlaylistDetail(playlist.id)) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
