package com.vayunmathur.youpipe.ui.dialogs

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/**
 * Share-target handler that adds the shared video to the mandatory Watch later playlist as soon
 * as its metadata finishes loading (the [VideoPage][com.vayunmathur.youpipe.ui.VideoPage] seeded
 * beneath this dialog drives the load), then dismisses itself back to the video.
 */
@Composable
fun AddToWatchLater(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
    videoID: Long,
) {
    val playlists by youPipeViewModel.playlists.collectAsStateWithLifecycle()
    val videoState by youPipeViewModel.videoState.collectAsStateWithLifecycle()

    val watchLater = playlists.firstOrNull { it.mandatory }
    val data = videoState.data
    var handled by remember { mutableStateOf(false) }

    LaunchedEffect(data, watchLater, videoState.error) {
        if (handled) return@LaunchedEffect
        if (videoState.error) {
            handled = true
            backStack.pop()
            return@LaunchedEffect
        }
        if (data != null && watchLater != null) {
            handled = true
            youPipeViewModel.playlistOps.addVideoToPlaylist(
                watchLater.id,
                VideoInfo(
                    data.title, videoID, data.duration, data.views,
                    data.uploadDate, data.thumbnailURL, data.author,
                ),
            )
            backStack.pop()
        }
    }

    Dialog({ backStack.pop() }) {
        Card {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.adding_to_watch_later))
            }
        }
    }
}
