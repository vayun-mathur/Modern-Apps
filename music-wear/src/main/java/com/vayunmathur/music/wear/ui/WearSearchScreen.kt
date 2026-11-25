package com.vayunmathur.music.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import com.vayunmathur.music.wear.R
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.domain.WearPageId
import com.vayunmathur.music.wear.domain.WearPageKey
import com.vayunmathur.music.wear.sync.WearLinkBridge

/** Search page: typed/dictated query is sent to the phone; paged results render as a track page. */
@Composable
fun WearSearchScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val pages by store.pages.collectAsState()
    val playback by store.playback.collectAsState()
    val page = WearPageId(WearPageKey.SEARCH, query = query)
    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item { WearTitle(stringResource(R.string.wear_search)) }
        item {
            BasicTextField(
                value = query,
                onValueChange = { next ->
                    query = next
                    if (next.length >= 2) WearLinkBridge.requestSearch(context, next)
                },
                textStyle = TextStyle(color = MaterialTheme.colors.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colors.onSurface),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (query.length < 2) {
            item { WearNote(stringResource(R.string.wear_search)) }
        } else {
            wearTrackItems(store::enqueueOp, page, pages[page], playback, onRoute)
        }
        wearBackItem { onRoute(WearRoute.Home) }
    }
}
