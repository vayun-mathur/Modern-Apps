package com.vayunmathur.music.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.wear.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.music.wear.data.WearMusicStore

/** Library info page: the phone app has no settings page, so this shows sync/cache truth only. */
@Composable
fun WearSettingsScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val pages by store.pages.collectAsState()
    val pending by store.pendingOps.collectAsState()
    Column {
        Text("Music on this watch")
        Text("Music and playback stay on your phone.")
        Text("Cached pages: " + pages.size)
        Text("Waiting for phone: " + pending.size)
    }
}
