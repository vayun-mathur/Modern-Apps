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
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import com.vayunmathur.music.wear.R
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.domain.WearMusicOp
import com.vayunmathur.music.wear.domain.WearOpKind

/** Up-next queue from the streamed playback state; tapping jumps via a phone op. */
@Composable
fun WearQueueScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val playback by store.playback.collectAsState()
    WearQueueContent(playback = playback, onOp = store::enqueueOp, onRoute = onRoute)
}

/** Stateless queue body behind [WearQueueScreen]: playback is a parameter for previews. */
@Composable
internal fun WearQueueContent(playback: com.vayunmathur.music.wear.domain.WearPlaybackState, onOp: (WearMusicOp) -> Unit, onRoute: (WearRoute) -> Unit) {
    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item { WearTitle(stringResource(R.string.wear_queue)) }
        if (playback.queue.isEmpty()) {
            item { WearNote(stringResource(R.string.wear_nothing_queued)) }
        } else {
            itemsIndexed(playback.queue, key = { _, track -> track.id }) { index, track ->
                Chip(
                    label = { Text((if (index == playback.queueIndex) "▶ " else "") + track.title, maxLines = 1) },
                    secondaryLabel = { Text(track.artist, maxLines = 1) },
                    onClick = {
                        onOp(
                            WearMusicOp(WearOpKind.PLAY_TRACKS, trackIds = playback.queue.map { it.id }, startIndex = index),
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        wearBackItem { onRoute(WearRoute.NowPlaying) }
    }
}
