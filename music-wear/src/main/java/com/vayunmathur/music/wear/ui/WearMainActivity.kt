package com.vayunmathur.music.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.sync.WearLinkBridge

/**
 * Watch music entry: mirrors the phone's pages (tabs, details, now playing,
 * queue, search) rendered only from cached page snapshots + streamed
 * playback state, so visited pages work offline. Playback itself always
 * happens on the phone; this UI only sends ops and reflects state.
 */
class WearMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vayunmathur.library.wearlink.WearLinkPermissions.requestIfNeeded(this)
        val store = WearMusicStore.get(this)
        WearLinkBridge.get(this)
        store.publishWatchState()
        setContent {
            MaterialTheme {
                var route by remember { mutableStateOf<WearRoute>(WearRoute.Home) }
                WearMusicRoot(store = store, route = route, onRoute = { route = it })
            }
        }
    }
}
