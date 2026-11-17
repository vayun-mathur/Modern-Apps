package com.vayunmathur.youpipe.ui.tv

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.tv.tvGridHeader
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.Comment
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.platform.DownloadManager
import com.vayunmathur.youpipe.platform.applyDownloadedStreams
import com.vayunmathur.youpipe.platform.clearVideoError
import com.vayunmathur.youpipe.platform.loadVideo
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * TV video detail: two panes, no backdrop. Left (~45%): the 16:9 thumbnail card with a
 * Play/Resume overlay action, title, channel row with Subscribe, stats, and wrapped action
 * buttons (see [TvVideoLeftPane]). Right: scrolling description, chapters, related videos
 * as a 2-column grid, comments.
 *
 * Binder mirrors the phone `VideoPage` (loadVideo, downloaded streams, DeArrow, history
 * progress). The player itself opens as a pushed player route from Play — this screen is
 * browse; playback semantics live in [TvPlayer].
 *
 * **WebView degrade:** the headless `SharedWebViewRuntime` PO-token stack works on Google TV
 * but version-lags. When the system WebView is missing or too old, extraction fails every
 * load — instead of a silent stall, this screen probes once and shows the focused
 * `tv_playback_unavailable` message. quickjs and media3 are TV-safe.
 */
@Composable
fun TvVideoPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    videoID: Long,
) {
    val context = LocalContext.current
    val downloadedFlow = remember(videoID) { ypvm.downloadedById(videoID) }
    val downloadedVideo by downloadedFlow.collectAsState(initial = null)
    val videoState by ypvm.videoState.collectAsState()
    var webViewStatus by remember(videoID) { mutableStateOf<TvWebViewStatus?>(null) }
    TvVideoProbeAndLoadEffect(videoID, ypvm, downloadedFlow, videoState.error) { webViewStatus = it }
    TvVideoDownloadedEffect(downloadedVideo, ypvm)
    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()
    val deArrowCache by ypvm.deArrowCache.collectAsState()
    val deArrowData = if (deArrowEnabled) deArrowCache[videoID] else null
    val videoData = videoState.data?.let { data ->
        data.copy(
            title = deArrowData?.title ?: data.title,
            thumbnailURL = deArrowData?.thumbnailUrl ?: data.thumbnailURL,
        )
    }
    val videoStreams = videoState.videoStreams
    val audioStreams = videoState.audioStreams
    val comments = videoState.comments
    val relatedVideos = videoState.relatedVideos
    val chapters = videoState.segments
    val history by ypvm.historyVideos.collectAsState()
    val historyById = remember(history) { history.associate { it.id to it.progress } }
    val subscriptions by ypvm.subscriptions.collectAsState()
    var showDownloadSheet by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val playlists by ypvm.playlists.collectAsState()
    val messenger = rememberMessenger()
    val addedToWatchLater = stringResource(R.string.tv_added_to_watch_later)
    val chaptersTitle = stringResource(R.string.tv_section_chapters)
    val relatedTitle = stringResource(R.string.label_related_videos)
    TvVideoErrorOrLoading(
        isError = videoState.error, downloadedVideo = downloadedVideo, webViewStatus = webViewStatus,
        isLoading = videoData == null, backStack = backStack, ypvm = ypvm,
    )?.let { return }
    val loadedVideoData = videoData ?: return
    val loadedArgs = TvVideoLoadedArgs(
        backStack, ypvm, videoID, loadedVideoData, deArrowData != null, videoStreams, audioStreams,
        comments, relatedVideos, chapters, historyById, subscriptions, playlists, messenger,
        addedToWatchLater, chaptersTitle, relatedTitle, downloadedVideo, playFocus,
        showDownloadSheet, showDescription, showOptions, { showDownloadSheet = true },
        { showDescription = true }, { showOptions = true }, { showDownloadSheet = false },
        { showDescription = false }, { showOptions = false },
    )
    TvVideoLoadedContent(loadedArgs, context)
}

internal data class TvVideoLoadedArgs(
    val backStack: NavBackStack<TvRoute>,
    val ypvm: YouPipeViewModel,
    val videoID: Long,
    val videoData: VideoData,
    val deArrowThumbnail: Boolean,
    val videoStreams: List<VideoStream>,
    val audioStreams: List<AudioStream>,
    val comments: List<Comment>,
    val relatedVideos: List<VideoInfo>,
    val chapters: List<VideoChapter>,
    val historyById: Map<Long, Long>,
    val subscriptions: List<com.vayunmathur.youpipe.data.Subscription>,
    val playlists: List<com.vayunmathur.youpipe.data.Playlist>,
    val messenger: com.vayunmathur.library.ui.Messenger,
    val addedToWatchLater: String,
    val chaptersTitle: String,
    val relatedTitle: String,
    val downloadedVideo: DownloadedVideo?,
    val playFocus: FocusRequester,
    val showDownloadSheet: Boolean,
    val showDescription: Boolean,
    val showOptions: Boolean,
    val onShowDownloadSheet: () -> Unit,
    val onShowDescription: () -> Unit,
    val onShowOptions: () -> Unit,
    val onDismissDownloadSheet: () -> Unit,
    val onDismissDescription: () -> Unit,
    val onDismissOptions: () -> Unit,
)

@Composable
private fun TvVideoLoadedContent(args: TvVideoLoadedArgs, context: android.content.Context) {
    val relatedRows = rememberTvVideoRows(args.relatedVideos, args.ypvm, args.historyById)
    val watchedMs = args.historyById[args.videoID] ?: 0L
    val resumeAvailable = watchedMs > 0L && args.videoData.duration > 0
    val existingSub = args.subscriptions.firstOrNull { it.channelID == args.videoData.authorURL }
    val optionRow = tvVideoOptionRow(args.videoID, args.videoData)
    TvInitialFocus(args.playFocus, args.videoID)
    val rightArgs = TvVideoRightArgs(
        args.chapters, args.chaptersTitle, relatedRows, args.relatedTitle, args.comments,
        { args.backStack.playFromDetails(args.videoID) }, { args.backStack.playVideo(it) },
    )
    Row(Modifier.fillMaxSize()) {
        TvVideoLeftPane(
            backStack = args.backStack, ypvm = args.ypvm, videoID = args.videoID,
            videoData = args.videoData, deArrowThumbnail = args.deArrowThumbnail,
            watchedMs = watchedMs, resumeAvailable = resumeAvailable, existingSub = existingSub,
            videoStreamsEmpty = args.videoStreams.isEmpty(), downloadedVideo = args.downloadedVideo,
            playlists = args.playlists, messenger = args.messenger,
            addedToWatchLater = args.addedToWatchLater, playFocus = args.playFocus,
            onShowDownloadSheet = args.onShowDownloadSheet,
            onShowDescription = args.onShowDescription, onShowOptions = args.onShowOptions,
            modifier = Modifier.weight(TV_LEFT_PANE_WEIGHT),
        )
        TvVideoRightPane(rightArgs)
    }
    val dialogArgs = TvVideoDialogArgs(
        args.showDescription, args.showOptions, args.showDownloadSheet, args.videoData, optionRow,
        args.videoStreams, args.audioStreams, args.videoID, args.backStack, args.ypvm,
        args.onDismissDescription, args.onDismissOptions, args.onDismissDownloadSheet,
    )
    TvVideoDialogs(dialogArgs, context)
}

@Composable
private fun TvVideoProbeAndLoadEffect(
    videoID: Long, ypvm: YouPipeViewModel, downloadedFlow: Flow<DownloadedVideo?>,
    isError: Boolean, onWebViewStatus: (TvWebViewStatus?) -> Unit,
) {
    // Probe once per video: missing/outdated WebView means extraction can never succeed,
    // so say so up front instead of stalling on the spinner.
    LaunchedEffect(videoID) {
        onWebViewStatus(probeTvWebView())
        if (TvLoadedVideo.id != videoID || isError) {
            TvLoadedVideo.id = videoID
            ypvm.loadVideo(videoID, downloadedFlow.first())
        }
    }
}

@Composable
private fun TvVideoDownloadedEffect(downloadedVideo: DownloadedVideo?, ypvm: YouPipeViewModel) {
    LaunchedEffect(downloadedVideo) {
        downloadedVideo?.let { ypvm.applyDownloadedStreams(it) }
    }
}

@Composable
private fun TvVideoErrorOrLoading(
    isError: Boolean, downloadedVideo: DownloadedVideo?, webViewStatus: TvWebViewStatus?,
    isLoading: Boolean, backStack: NavBackStack<TvRoute>, ypvm: YouPipeViewModel,
): Unit? {
    // Error / unavailable states: a focused message, never a silent spinner.
    // Downloads play locally with no PO-token, so only the streaming path is gated
    // on WebView status; a downloaded file renders regardless.
    if (isError || (downloadedVideo == null && webViewStatus is TvWebViewStatus.Unavailable)) {
        val errorFocus = remember { FocusRequester() }
        TvInitialFocus(errorFocus)
        TvEmptyState(
            title = if (downloadedVideo == null && webViewStatus is TvWebViewStatus.Unavailable) {
                stringResource(R.string.tv_playback_unavailable)
            } else {
                stringResource(R.string.video_load_error)
            },
            isError = true,
            action = {
                TvButton(
                    text = stringResource(R.string.action_go_back),
                    modifier = Modifier.focusRequester(errorFocus),
                    onClick = {
                        ypvm.clearVideoError()
                        backStack.pop()
                    },
                )
            },
        )
        return Unit
    }
    if (isLoading) {
        TvLoading()
        return Unit
    }
    return null
}

private fun tvVideoOptionRow(videoID: Long, videoData: VideoData): TvVideoRow {
    return TvVideoRow(
        row = com.vayunmathur.youpipe.platform.VideoRowState(
            videoID = videoID,
            title = videoData.title,
            thumbnailURL = videoData.thumbnailURL,
            author = videoData.author,
            stats = "",
            channelKey = videoData.author.lowercase(),
        ),
        info = VideoInfo(
            videoData.title, videoID, videoData.duration, videoData.views,
            videoData.uploadDate, videoData.thumbnailURL, videoData.author,
        ),
    )
}

internal data class TvVideoRightArgs(
    val chapters: List<VideoChapter>,
    val chaptersTitle: String,
    val relatedRows: List<TvVideoRow>,
    val relatedTitle: String,
    val comments: List<Comment>,
    val onChapter: () -> Unit,
    val onRelated: (Long) -> Unit,
)

@Composable
private fun RowScope.TvVideoRightPane(args: TvVideoRightArgs) {
    // Right: scrolling body — chapters, related, comments.
    TvGrid(columns = 2, modifier = Modifier.weight(TV_RIGHT_PANE_WEIGHT).fillMaxHeight()) {
        if (args.chapters.isNotEmpty()) {
            tvGridHeader(
                key = "chapters-header",
                title = args.chaptersTitle,
                first = true,
            )
            items(args.chapters.size, key = { args.chapters[it].time }) { index ->
                TvChapterCard(
                    chapter = args.chapters[index],
                    onClick = args.onChapter,
                )
            }
        }
        if (args.relatedRows.isNotEmpty()) {
            tvGridHeader(
                key = "related-header",
                title = args.relatedTitle,
                first = args.chapters.isEmpty(),
            )
            items(args.relatedRows.size, key = { args.relatedRows[it].row.videoID }) { index ->
                val item = args.relatedRows[index]
                TvVideoCard(
                    item = item,
                    onOpen = { args.onRelated(item.row.videoID) },
                )
            }
        }
        if (args.comments.isNotEmpty()) {
            tvFullWidth(key = "comments") {
                TvVideoComments(args.comments)
            }
        }
    }
}

internal data class TvVideoDialogArgs(
    val showDescription: Boolean,
    val showOptions: Boolean,
    val showDownloadSheet: Boolean,
    val videoData: VideoData,
    val optionRow: TvVideoRow,
    val videoStreams: List<VideoStream>,
    val audioStreams: List<AudioStream>,
    val videoID: Long,
    val backStack: NavBackStack<TvRoute>,
    val ypvm: YouPipeViewModel,
    val onDismissDescription: () -> Unit,
    val onDismissOptions: () -> Unit,
    val onDismissDownloadSheet: () -> Unit,
)


internal const val TV_LEFT_PANE_WEIGHT = 0.45f
internal const val TV_RIGHT_PANE_WEIGHT = 0.55f
