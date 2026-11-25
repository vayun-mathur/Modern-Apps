package com.vayunmathur.music.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import com.vayunmathur.music.wear.R
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.domain.WearPageId
import com.vayunmathur.music.wear.domain.WearPageKey

/** Albums tab, mirroring the phone's Albums page ordering. */
@Composable
fun WearAlbumListScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val page = WearPageId(WearPageKey.ALBUMS)
    WearPageLoader(store, page)
    val pages by store.pages.collectAsState()
    val snapshot = pages[page]
    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item { WearTitle(stringResource(R.string.wear_albums)) }
        if (snapshot == null) {
            item { WearNote(stringResource(R.string.wear_loading)) }
        } else if (snapshot.albums.isEmpty()) {
            item { WearNote(stringResource(R.string.wear_empty)) }
        } else {
            items(snapshot.albums, key = { it.id }) { album ->
                Chip(
                    label = { Text(album.name, maxLines = 1) },
                    secondaryLabel = { Text(album.artistName, maxLines = 1) },
                    onClick = { onRoute(WearRoute.AlbumDetail(album.id)) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        wearBackItem { onRoute(WearRoute.Home) }
    }
}
