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

@Composable
internal fun TvVideoDialogs(args: TvVideoDialogArgs, context: android.content.Context) {
    if (args.showDescription) {
        TvDescriptionDialog(args.videoData.title, args.videoData.description, args.onDismissDescription)
    }
    if (args.showOptions) {
        TvVideoOptionsDialog(
            item = args.optionRow,
            onDismiss = args.onDismissOptions,
            onOpen = { args.backStack.playFromDetails(args.videoID) },
            ypvm = args.ypvm,
        )
    }
    val sheetData = args.videoData
    if (args.showDownloadSheet && args.videoStreams.isNotEmpty()) {
        TvDownloadSheet(
            videoStreams = args.videoStreams,
            audioStreams = args.audioStreams,
            onConfirm = { videoUrl, audioUrl ->
                DownloadManager.enqueueDownload(
                    context,
                    VideoInfo(
                        sheetData.title, args.videoID, sheetData.duration, sheetData.views,
                        sheetData.uploadDate, sheetData.thumbnailURL, sheetData.author,
                    ),
                    videoUrl,
                    audioUrl,
                )
                args.onDismissDownloadSheet()
            },
            onDismiss = args.onDismissDownloadSheet,
        )
    }
}
