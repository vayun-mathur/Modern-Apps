package com.vayunmathur.music.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.domain.WearPageId
import com.vayunmathur.music.wear.domain.WearPageKey
import com.vayunmathur.music.wear.sync.WearLinkBridge

/** Search page: typed/dictated query is sent to the phone; paged results render as a track page. */
@Composable
fun WearSearchScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    Column {
        Text("Search your library")
        BasicTextField(
            value = query,
            onValueChange = { next ->
                query = next
                if (next.length >= 2) WearLinkBridge.requestSearch(context, next)
            },
            textStyle = TextStyle(color = MaterialTheme.colors.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colors.onSurface),
        )
        WearTrackRows(store, WearPageId(WearPageKey.SEARCH, query = query), onRoute)
    }
}
