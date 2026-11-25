package com.vayunmathur.music.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.wear.compose.material.Text
import androidx.compose.runtime.Composable
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.domain.WearPageId

/** Watch-sized track list for Home / album / artist / playlist detail pages. */
@Composable
fun WearTrackListScreen(store: WearMusicStore, page: WearPageId, title: String, onRoute: (WearRoute) -> Unit) {
    WearPageLoader(store, page)
    Column {
        Text(title)
        WearTrackRows(store, page, onRoute)
    }
}
