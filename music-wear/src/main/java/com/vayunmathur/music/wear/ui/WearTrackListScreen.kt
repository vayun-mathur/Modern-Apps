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
    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item { WearTitle(title) }
        wearTrackItems(store::enqueueOp, page, pages[page], playback, onRoute)
        wearBackItem(onBack)
    }
}
