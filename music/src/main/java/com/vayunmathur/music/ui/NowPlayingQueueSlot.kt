package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The Expanded queue slot. SongScreen fills it from the ViewModel's queue when
 * there is one; previews leave it empty.
 *
 * Dead code kept for the previews' call sites: SongScreen now wires
 * [NowPlayingQueue] directly, but the screenshot previews still pass this
 * slot. Do not add new callers.
 */
@Composable
fun NowPlayingQueueSlot(
    queue: @Composable (Modifier) -> Unit = {},
) {
    queue(Modifier.fillMaxSize())
}
