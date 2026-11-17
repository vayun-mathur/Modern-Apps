package com.vayunmathur.youpipe.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.countString
import com.vayunmathur.youpipe.ui.uploadTimeAgo
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/** Everything the player overlay shows around the video, and what its buttons do. */
internal class TvPlayerChrome(
    val byline: String,
    val avatarUrl: String,
    val subscribed: Boolean,
    val related: List<TvVideoRow>,
    val onToggleSubscribe: () -> Unit,
    val onChannel: () -> Unit,
    val onDetails: () -> Unit,
    val onWatchLater: () -> Unit,
    val onSaveToPlaylist: () -> Unit,
    val onPlayRelated: (Long) -> Unit,
)

@Composable
internal fun rememberTvPlayerChrome(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    videoID: Long,
    data: VideoData,
    relatedVideos: List<VideoInfo>,
): TvPlayerChrome {
    val context = LocalContext.current
    val resources = LocalResources.current
    val subscriptions by ypvm.subscriptions.collectAsState()
    val playlists by ypvm.playlists.collectAsState()
    val history by ypvm.historyVideos.collectAsState()
    val historyById = remember(history) { history.associate { it.id to it.progress } }
    val related = rememberTvVideoRows(relatedVideos, ypvm, historyById)
    val messenger = rememberMessenger()
    val addedToWatchLater = stringResource(R.string.tv_added_to_watch_later)
    val existingSub = subscriptions.firstOrNull { it.channelID == data.authorURL }
    val byline = if (data.views < 0) {
        resources.getString(R.string.video_info_paid_format, data.author, uploadTimeAgo(context, data.uploadDate))
    } else {
        resources.getString(
            R.string.video_info_format,
            data.author,
            countString(context, data.views),
            uploadTimeAgo(context, data.uploadDate),
        )
    }
    return TvPlayerChrome(
        byline = byline,
        avatarUrl = data.authorThumbnail,
        subscribed = existingSub != null,
        related = related,
        onToggleSubscribe = {
            if (existingSub == null) {
                ypvm.upsertSubscription(
                    Subscription(name = data.author, channelID = data.authorURL, avatarURL = data.authorThumbnail),
                )
            } else {
                ypvm.deleteSubscription(existingSub)
            }
        },
        onChannel = { backStack.add(TvRoute.Channel(data.authorURL)) },
        onDetails = { backStack.add(TvRoute.Video(videoID)) },
        onWatchLater = {
            playlists.firstOrNull { it.mandatory }?.let { watchLater ->
                ypvm.playlistOps.addVideoToPlaylist(
                    watchLater.id,
                    VideoInfo(
                        data.title, videoID, data.duration, data.views,
                        data.uploadDate, data.thumbnailURL, data.author,
                    ),
                )
                messenger.show(addedToWatchLater)
            }
        },
        onSaveToPlaylist = { backStack.add(TvRoute.AddToPlaylist(videoID, includeWatchLater = false)) },
        onPlayRelated = { backStack.setLast(TvRoute.VideoPlayerRoute(it)) },
    )
}

/** Selecting a video on TV plays it; details live behind the player's Details button. */
internal fun NavBackStack<TvRoute>.playVideo(videoID: Long) {
    add(TvRoute.VideoPlayerRoute(videoID))
}

/**
 * Play from the details page: if the details were opened from this video's player, go back to
 * it (playback never stopped) instead of stacking a second player.
 */
internal fun NavBackStack<TvRoute>.playFromDetails(videoID: Long) {
    val entries = backStack
    val previous = entries.getOrNull(entries.size - 2)
    if (previous is TvRoute.VideoPlayerRoute && previous.videoID == videoID) pop() else playVideo(videoID)
}

/** Which video `videoState` currently holds; `VideoData` carries no ID of its own. */
internal object TvLoadedVideo {
    var id: Long? = null
}
