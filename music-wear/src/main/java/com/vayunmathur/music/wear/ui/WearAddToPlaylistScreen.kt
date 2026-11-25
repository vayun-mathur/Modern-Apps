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
import com.vayunmathur.music.wear.domain.WearMusicOp
import com.vayunmathur.music.wear.domain.WearOpKind
import com.vayunmathur.music.wear.domain.WearPageId
import com.vayunmathur.music.wear.domain.WearPageKey

/** Add-to-playlist sheet (phone AddToPlaylistDialog): the op executes on the phone. */
@Composable
fun WearAddToPlaylistScreen(store: WearMusicStore, musicId: Long, onRoute: (WearRoute) -> Unit) {
    val page = WearPageId(WearPageKey.PLAYLISTS)
    WearPageLoader(store, page)
    val pages by store.pages.collectAsState()
    val snapshot = pages[page]
    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item { WearTitle(stringResource(R.string.wear_add_to_playlist)) }
        if (snapshot == null) {
            item { WearNote(stringResource(R.string.wear_loading)) }
        } else if (snapshot.playlists.isEmpty()) {
            item { WearNote(stringResource(R.string.wear_empty)) }
        } else {
            items(snapshot.playlists, key = { it.id }) { playlist ->
                Chip(
                    label = { Text(playlist.name, maxLines = 1) },
                    onClick = {
                        store.enqueueOp(WearMusicOp(WearOpKind.ADD_TO_PLAYLIST, trackIds = listOf(musicId), entityId = playlist.id))
                        onRoute(WearRoute.NowPlaying)
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        wearBackItem { onRoute(WearRoute.NowPlaying) }
    }
}
