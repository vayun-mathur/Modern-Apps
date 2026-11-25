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

/** Artists tab, mirroring the phone's Artists page ordering. */
@Composable
fun WearArtistListScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val page = WearPageId(WearPageKey.ARTISTS)
    WearPageLoader(store, page)
    val pages by store.pages.collectAsState()
    Column {
        Text("Artists")
        LazyColumn {
            items(pages[page]?.artists.orEmpty()) { artist ->
                Chip(label = { Text(artist.name) }, onClick = { onRoute(WearRoute.ArtistDetail(artist.id)) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
