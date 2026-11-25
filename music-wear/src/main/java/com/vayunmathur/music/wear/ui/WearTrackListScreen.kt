package com.vayunmathur.music.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.domain.WearPageId

/** Watch-sized track list for album / artist / playlist detail pages. */
@Composable
fun WearTrackListScreen(
    store: WearMusicStore,
    page: WearPageId,
    title: String,
    onRoute: (WearRoute) -> Unit,
    onBack: () -> Unit,
) {
    WearPageLoader(store, page)
    val pages by store.pages.collectAsState()
    val playback by store.playback.collectAsState()
    WearTrackListContent(page = page, title = title, snapshot = pages[page], playback = playback, onEnqueueOp = store::enqueueOp, onRoute = onRoute, onBack = onBack)
}

/** Stateless track-list body behind [WearTrackListScreen]: snapshot/playback are parameters for previews. */
@Composable
internal fun WearTrackListContent(
    page: WearPageId,
    title: String,
    snapshot: com.vayunmathur.music.wear.domain.WearPageSnapshot?,
    playback: com.vayunmathur.music.wear.domain.WearPlaybackState,
    onEnqueueOp: (com.vayunmathur.music.wear.domain.WearMusicOp) -> Unit,
    onRoute: (WearRoute) -> Unit,
    onBack: () -> Unit,
) {
    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item { WearTitle(title) }
        wearTrackItems(onEnqueueOp, page, snapshot, playback, onRoute)
        wearBackItem(onBack)
    }
}
