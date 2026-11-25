package com.vayunmathur.music.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.domain.WearMusicOp
import com.vayunmathur.music.wear.domain.WearOpKind

/** Now-playing (phone Route.Song): transport ops go to the phone player; no audio plays here. */
@Composable
fun WearNowPlayingScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val playback by store.playback.collectAsState()
    Column {
        Text(playback.title.ifEmpty { "Nothing playing" })
        Text(playback.artist)
        Text(
            formatMs(playback.positionMs) + " / " + formatMs(playback.durationMs),
        )
        Chip(
            label = { Text("Seek -30s") },
            onClick = {
                store.enqueueOp(WearMusicOp(WearOpKind.SEEK, positionMs = (playback.positionMs - 30_000L).coerceAtLeast(0L)))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Chip(
            label = { Text("Seek +30s") },
            onClick = {
                store.enqueueOp(WearMusicOp(WearOpKind.SEEK, positionMs = playback.positionMs + 30_000L))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { store.enqueueOp(WearMusicOp(WearOpKind.SKIP_PREVIOUS)) }) { Text("Previous") }
        Button(onClick = { store.enqueueOp(WearMusicOp(WearOpKind.TOGGLE_PLAY_PAUSE)) }) {
            Text(if (playback.isPlaying) "Pause" else "Play")
        }
        Button(onClick = { store.enqueueOp(WearMusicOp(WearOpKind.SKIP_NEXT)) }) { Text("Next") }
        Chip(
            label = { Text("Shuffle: " + if (playback.shuffle) "on" else "off") },
            onClick = { store.enqueueOp(WearMusicOp(WearOpKind.TOGGLE_SHUFFLE)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Chip(
            label = { Text("Repeat") },
            onClick = { store.enqueueOp(WearMusicOp(WearOpKind.CYCLE_REPEAT)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Chip(
            label = { Text("Queue") },
            onClick = { onRoute(WearRoute.Queue) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (playback.lyricsSnippet.isNotEmpty()) Text(playback.lyricsSnippet)
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    return (totalSeconds / 60).toString() + ":" + (totalSeconds % 60).toString().padStart(2, '0')
}
