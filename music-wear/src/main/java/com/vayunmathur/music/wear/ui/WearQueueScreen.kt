package com.vayunmathur.music.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Chip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.wear.compose.material.Chip
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.domain.WearMusicOp
import com.vayunmathur.music.wear.domain.WearOpKind

/** Up-next queue from the streamed playback state; tapping jumps via a phone op. */
@Composable
fun WearQueueScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val playback by store.playback.collectAsState()
    Column {
        Text("Up next (on your phone)")
        LazyColumn {
            itemsIndexed(playback.queue) { index, track ->
                Chip(
                    label = { Text((if (index == playback.queueIndex) "\u25B6 " else "") + track.title) },
                    onClick = {
                        store.enqueueOp(
                            WearMusicOp(WearOpKind.PLAY_TRACKS, trackIds = playback.queue.map { it.id }, startIndex = index),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
