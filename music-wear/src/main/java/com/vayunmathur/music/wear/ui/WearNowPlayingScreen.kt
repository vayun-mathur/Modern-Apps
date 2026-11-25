package com.vayunmathur.music.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.music.wear.R
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.domain.WearMusicOp
import com.vayunmathur.music.wear.domain.WearOpKind

/** Now-playing (phone Route.Song): transport ops go to the phone player; no audio plays here. */
@Composable
fun WearNowPlayingScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val playback by store.playback.collectAsState()
    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item { WearTitle(stringResource(R.string.wear_now_playing)) }
        item {
            Text(
                playback.title.ifEmpty { stringResource(R.string.wear_nothing_playing) },
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (playback.artist.isNotBlank()) {
            item {
                Text(
                    playback.artist,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
        item {
            Text(
                formatMs(playback.positionMs) + " / " + formatMs(playback.durationMs),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        item {
            Chip(
                label = { Text(stringResource(R.string.wear_seek_back), maxLines = 1) },
                onClick = {
                    store.enqueueOp(WearMusicOp(WearOpKind.SEEK, positionMs = (playback.positionMs - 30_000L).coerceAtLeast(0L)))
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                label = { Text(stringResource(R.string.wear_seek_forward), maxLines = 1) },
                onClick = {
                    store.enqueueOp(WearMusicOp(WearOpKind.SEEK, positionMs = playback.positionMs + 30_000L))
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                label = { Text(stringResource(R.string.wear_previous), maxLines = 1) },
                onClick = { store.enqueueOp(WearMusicOp(WearOpKind.SKIP_PREVIOUS)) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                label = {
                    Text(
                        stringResource(if (playback.isPlaying) R.string.wear_pause else R.string.wear_play),
                        maxLines = 1,
                    )
                },
                onClick = { store.enqueueOp(WearMusicOp(WearOpKind.TOGGLE_PLAY_PAUSE)) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                label = { Text(stringResource(R.string.wear_next), maxLines = 1) },
                onClick = { store.enqueueOp(WearMusicOp(WearOpKind.SKIP_NEXT)) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                label = {
                    Text(
                        stringResource(R.string.wear_shuffle) + ": " + if (playback.shuffle) "on" else "off",
                        maxLines = 1,
                    )
                },
                onClick = { store.enqueueOp(WearMusicOp(WearOpKind.TOGGLE_SHUFFLE)) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                label = { Text(stringResource(R.string.wear_repeat), maxLines = 1) },
                onClick = { store.enqueueOp(WearMusicOp(WearOpKind.CYCLE_REPEAT)) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                label = { Text(stringResource(R.string.wear_queue), maxLines = 1) },
                onClick = { onRoute(WearRoute.Queue) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        playback.trackId?.let { trackId ->
            item {
                Chip(
                    label = { Text(stringResource(R.string.wear_add_to_playlist), maxLines = 1) },
                    onClick = { onRoute(WearRoute.AddToPlaylist(trackId)) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        if (playback.lyricsSnippet.isNotEmpty()) {
            item { WearNote(playback.lyricsSnippet) }
        }
        wearBackItem { onRoute(WearRoute.Home) }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    return (totalSeconds / 60).toString() + ":" + (totalSeconds % 60).toString().padStart(2, '0')
}
