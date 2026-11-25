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

/** Artists tab, mirroring the phone's Artists page ordering. */
@Composable
fun WearArtistListScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val page = WearPageId(WearPageKey.ARTISTS)
    WearPageLoader(store, page)
    val pages by store.pages.collectAsState()
    val snapshot = pages[page]
    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item { WearTitle(stringResource(R.string.wear_artists)) }
        if (snapshot == null) {
            item { WearNote(stringResource(R.string.wear_loading)) }
        } else if (snapshot.artists.isEmpty()) {
            item { WearNote(stringResource(R.string.wear_empty)) }
        } else {
            items(snapshot.artists, key = { it.id }) { artist ->
                Chip(
                    label = { Text(artist.name, maxLines = 1) },
                    onClick = { onRoute(WearRoute.ArtistDetail(artist.id)) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        wearBackItem { onRoute(WearRoute.Home) }
    }
}
