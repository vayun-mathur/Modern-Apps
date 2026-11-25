package com.vayunmathur.music.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.vayunmathur.music.wear.R
import com.vayunmathur.music.wear.data.WearMusicStore

/** Library info page: the phone app has no settings page, so this shows sync/cache truth only. */
@Composable
fun WearSettingsScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val pages by store.pages.collectAsState()
    val pending by store.pendingOps.collectAsState()
    WearSettingsContent(cachedPages = pages.size, pendingCount = pending.size, onRoute = onRoute)
}

/** Stateless settings body behind [WearSettingsScreen]: counts are parameters for previews. */
@Composable
internal fun WearSettingsContent(cachedPages: Int, pendingCount: Int, onRoute: (WearRoute) -> Unit) {
    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item { WearTitle(stringResource(R.string.wear_settings)) }
        item { WearNote("Music and playback stay on your phone.") }
        item { WearNote("Cached pages: " + cachedPages) }
        item { WearNote("Waiting for phone: " + pendingCount) }
        if (pendingCount > 0) {
            item { WearNote(stringResource(R.string.wear_no_phone)) }
        }
        wearBackItem { onRoute(WearRoute.Home) }
    }
}
