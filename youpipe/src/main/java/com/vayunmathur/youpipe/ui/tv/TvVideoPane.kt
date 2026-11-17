package com.vayunmathur.youpipe.ui.tv

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvAspect
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDimens
import com.vayunmathur.library.tv.TvMediaCard
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.Messenger
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.network.videoIDtoURL

/**
 * The video page's left pane (~45%): the 16:9 thumbnail card with Play/Resume, title,
 * channel row with Subscribe, stats, and FlowRow-wrapped action buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RowScope.TvVideoLeftPane(
    backStack: NavBackStack<TvRoute>, ypvm: YouPipeViewModel, videoID: Long,
    videoData: VideoData, deArrowThumbnail: Boolean, watchedMs: Long,
    resumeAvailable: Boolean, existingSub: Subscription?, videoStreamsEmpty: Boolean,
    downloadedVideo: DownloadedVideo?, playlists: List<Playlist>, messenger: Messenger,
    addedToWatchLater: String, playFocus: FocusRequester, onShowDownloadSheet: () -> Unit,
    onShowDescription: () -> Unit, onShowOptions: () -> Unit, modifier: Modifier = Modifier,
) {
    Column(
        modifier.weight(TV_LEFT_PANE_WEIGHT).fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(start = tvContentStart(), end = 12.dp, top = TvDimens.SafeVertical, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TvVideoThumbnailCard(videoID, videoData, deArrowThumbnail, watchedMs) {
            backStack.playFromDetails(videoID)
        }
        TvVideoPlayButton(resumeAvailable, playFocus) { backStack.playFromDetails(videoID) }
        Text(
            text = videoData.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TvVideoChannelRow(videoData, existingSub, ypvm)
        TvVideoActions(
            backStack = backStack, ypvm = ypvm, videoID = videoID, videoData = videoData,
            downloadedVideo = downloadedVideo, playlists = playlists, messenger = messenger,
            addedToWatchLater = addedToWatchLater, videoStreamsEmpty = videoStreamsEmpty,
            onShowDownloadSheet = onShowDownloadSheet, onShowDescription = onShowDescription,
            onShowOptions = onShowOptions,
        )
        TvVideoDescriptionPreview(videoData)
    }
}

@Composable
private fun TvVideoThumbnailCard(
    videoID: Long, videoData: VideoData, deArrowThumbnail: Boolean,
    watchedMs: Long, onPlay: () -> Unit,
) {
    val context = LocalContext.current
    TvMediaCard(
        onClick = onPlay,
        aspectRatio = TvAspect.Landscape,
        badge = tvDurationBadge(videoData.duration),
        progress = if (videoData.duration > 0) {
            (watchedMs / 1_000.0 / videoData.duration).toFloat().coerceIn(0f, 1f).takeIf { it > 0f }
        } else {
            null
        },
        image = {
            if (videoData.thumbnailURL.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(videoData.thumbnailURL)
                        .memoryCacheKey(
                            "video-thumb-$videoID-" + if (deArrowThumbnail) "da" else "yt",
                        )
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        },
    )
}

@Composable
private fun TvVideoPlayButton(resumeAvailable: Boolean, playFocus: FocusRequester, onPlay: () -> Unit) {
    TvButton(
        text = stringResource(
            if (resumeAvailable) R.string.tv_action_resume else UiR.string.play,
        ),
        onClick = onPlay,
        icon = { IconPlay() },
        primary = true,
        modifier = Modifier.focusRequester(playFocus).fillMaxWidth(),
    )
}

@Composable
private fun TvVideoChannelRow(videoData: VideoData, existingSub: Subscription?, ypvm: YouPipeViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TvVideoAuthorAvatar(videoData)
        TvVideoAuthorMeta(videoData)
        TvVideoSubscribeButton(videoData, existingSub, ypvm)
    }
}

@Composable
private fun TvVideoAuthorAvatar(videoData: VideoData) {
    val context = LocalContext.current
    if (videoData.authorThumbnail.isNotEmpty()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(videoData.authorThumbnail)
                .memoryCacheKey("author-thumb-${videoData.authorURL}")
                .build(),
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun RowScope.TvVideoAuthorMeta(videoData: VideoData) {
    val context = LocalContext.current
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
        Text(
            text = videoData.author,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = authorStatsText(context, videoData),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun authorStatsText(context: Context, videoData: VideoData): String =
    if (videoData.views < 0) {
        context.getString(
            R.string.video_stat_paid_format,
            com.vayunmathur.youpipe.ui.uploadTimeAgo(context, videoData.uploadDate),
        )
    } else {
        context.getString(
            R.string.video_stat_format,
            com.vayunmathur.youpipe.ui.countString(context, videoData.views),
            com.vayunmathur.youpipe.ui.uploadTimeAgo(context, videoData.uploadDate),
        )
    }

@Composable
private fun TvVideoSubscribeButton(
    videoData: VideoData,
    existingSub: Subscription?,
    ypvm: YouPipeViewModel,
) {
    TvButton(
        text = stringResource(
            if (existingSub == null) R.string.action_subscribe
            else R.string.action_unsubscribe,
        ),
        onClick = {
            if (existingSub == null) {
                ypvm.upsertSubscription(
                    Subscription(
                        name = videoData.author,
                        channelID = videoData.authorURL,
                        avatarURL = videoData.authorThumbnail,
                    ),
                )
            } else {
                ypvm.deleteSubscription(existingSub)
            }
        },
        primary = existingSub == null,
        modifier = Modifier.padding(start = 8.dp),
    )
}

@Composable
private fun TvVideoDescriptionPreview(videoData: VideoData) {
    if (videoData.description.isNotBlank()) {
        Column(Modifier.widthIn(max = 640.dp)) {
            Text(
                text = videoData.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
            )
        }
    }
}

/** The wrapped action buttons under the channel row. Split for FileLength. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvVideoActions(
    backStack: NavBackStack<TvRoute>, ypvm: YouPipeViewModel, videoID: Long,
    videoData: VideoData, downloadedVideo: DownloadedVideo?, playlists: List<Playlist>,
    messenger: Messenger, addedToWatchLater: String, videoStreamsEmpty: Boolean,
    onShowDownloadSheet: () -> Unit, onShowDescription: () -> Unit, onShowOptions: () -> Unit,
) {
    val context = LocalContext.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TvVideoPlaylistActions(backStack, ypvm, videoID, videoData, playlists, messenger, addedToWatchLater)
        TvVideoDownloadAction(downloadedVideo, videoStreamsEmpty, ypvm, onShowDownloadSheet)
        TvButton(
            text = stringResource(R.string.tv_action_go_to_channel),
            onClick = { backStack.add(TvRoute.Channel(videoData.authorURL)) },
        )
        TvButton(
            text = stringResource(R.string.label_description),
            onClick = onShowDescription,
            icon = { IconInfo() },
        )
        TvButton(
            text = stringResource(UiR.string.share),
            onClick = { ExternalIntents.shareText(context, videoIDtoURL(videoID)) },
            icon = { IconShare() },
        )
        TvButton(
            text = stringResource(R.string.tv_action_video_options),
            onClick = onShowOptions,
            icon = { IconMoreVert() },
        )
    }
}

@Composable
private fun TvVideoPlaylistActions(
    backStack: NavBackStack<TvRoute>, ypvm: YouPipeViewModel, videoID: Long,
    videoData: VideoData, playlists: List<Playlist>, messenger: Messenger,
    addedToWatchLater: String,
) {
    TvButton(
        text = stringResource(R.string.add_to_playlist),
        onClick = { backStack.add(TvRoute.AddToPlaylist(videoID, includeWatchLater = false)) },
        icon = { IconList() },
    )
    TvButton(
        text = stringResource(R.string.playlist_watch_later),
        onClick = {
            playlists.firstOrNull { it.mandatory }?.let { watchLater ->
                ypvm.playlistOps.addVideoToPlaylist(
                    watchLater.id,
                    VideoInfo(
                        videoData.title, videoID, videoData.duration, videoData.views,
                        videoData.uploadDate, videoData.thumbnailURL, videoData.author,
                    ),
                )
                messenger.show(addedToWatchLater)
            }
        },
        icon = { IconSchedule() },
    )
}

@Composable
private fun TvVideoDownloadAction(
    downloadedVideo: DownloadedVideo?, videoStreamsEmpty: Boolean,
    ypvm: YouPipeViewModel, onShowDownloadSheet: () -> Unit,
) {
    if (downloadedVideo != null) {
        TvButton(
            text = stringResource(UiR.string.delete),
            onClick = { downloadedVideo.let { ypvm.deleteDownloadedVideo(it) } },
            icon = { IconDownload() },
        )
    } else if (!videoStreamsEmpty) {
        TvButton(
            text = stringResource(R.string.download),
            onClick = onShowDownloadSheet,
            icon = { IconDownload() },
        )
    }
}
