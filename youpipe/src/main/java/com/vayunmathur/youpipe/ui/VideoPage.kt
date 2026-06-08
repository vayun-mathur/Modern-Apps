package com.vayunmathur.youpipe.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vayunmathur.library.util.NavBackStack
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vayunmathur.library.util.round
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.util.DownloadManager
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.findActivity
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.periodUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class VideoChapter(val time: Int, val title: String, val previewURL: String?)
data class AudioStream(val url: String, val bitrate: Int, val language: String, val codec: String, val size: Long)
data class VideoStream(val url: String, val width: Int, val height: Int, val bitrate: Int, val fps: Int, val quality: String, val codec: String, val size: Long)
data class VideoData(val title: String, val views: Long, val duration: Long, val uploadDate: Instant, val thumbnailURL: String, val author: String, val authorURL: String, val authorThumbnail: String, val description: String)
data class Comment(val text: String, val author: String, val likes: Int, val dislikes: Int)

@Composable
fun LockScreenOrientation(orientation: Int) {
    val context = LocalContext.current
    DisposableEffect(orientation) {
        val activity = context.findActivity()
        val originalOrientation = activity.requestedOrientation
        activity.requestedOrientation = orientation

        onDispose {
            // Restore original orientation when leaving the screen
            activity.requestedOrientation = originalOrientation
        }
    }
}

@Composable
fun VideoPage(
    backStack: NavBackStack<Route>,
    ypvm: YouPipeViewModel,
    videoID: Long,
) {
    val downloadedFlow = remember(videoID) { ypvm.downloadedById(videoID) }
    val downloadedVideo by downloadedFlow.collectAsState(initial = null)
    val videoState by ypvm.videoState.collectAsState()

    LaunchedEffect(videoID) {
        ypvm.loadVideo(videoID, downloadedVideo)
    }
    LaunchedEffect(downloadedVideo) {
        downloadedVideo
        downloadedVideo?.let { ypvm.applyDownloadedStreams(it) }
    }

    val videoData = videoState.data?.let { data ->
        data.copy(
            title = videoState.deArrowTitle ?: data.title,
            thumbnailURL = videoState.deArrowThumbnail ?: data.thumbnailURL
        )
    }
    val videoStreams = videoState.videoStreams
    val audioStreams = videoState.audioStreams
    val segments = videoState.segments
    val comments = videoState.comments
    val relatedVideos = videoState.relatedVideos

    if (videoState.error) {
        Dialog({
            ypvm.clearVideoError()
            backStack.pop()
        }) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.video_load_error))
                    Spacer(Modifier.height(8.dp))
                    Button({
                        ypvm.clearVideoError()
                        backStack.pop()
                    }) {
                        Text(stringResource(R.string.action_go_back))
                    }
                }
            }
        }
    }

    var isFullscreen by remember { mutableStateOf(false) }

    val view = LocalView.current
    LaunchedEffect(isFullscreen) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)

        if (isFullscreen) {
            // Hide both status bar and navigation bar
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // Make it so they only reappear with a swipe and don't resize the layout
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if(isFullscreen) {
        LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    }


    Scaffold { paddingValues ->
        val modifier = if(isFullscreen) Modifier.padding(top = paddingValues.calculateTopPadding(), bottom = paddingValues.calculateBottomPadding()) else Modifier.padding(paddingValues)
        Column(modifier) {
            videoData?.let { videoData ->
                if (videoStreams.isNotEmpty()) {
                    VideoPlayer(ypvm, VideoInfo(videoData.title, videoID, videoData.duration, videoData.views, videoData.uploadDate, videoData.thumbnailURL, videoData.author), videoStreams, audioStreams, segments, isFullscreen) {
                        isFullscreen = it
                    }
                }
                VideoDetails(backStack, ypvm, videoData, videoID, videoStreams, audioStreams)

                if(!isFullscreen) {
                    val pagerState = rememberPagerState(pageCount = { 3 })
                    val coroutineScope = rememberCoroutineScope()

                    Column {
                        // 2. The TabRow synchronized with the pager
                        SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
                            Tab(
                                selected = pagerState.currentPage == 0,
                                onClick = {
                                    coroutineScope.launch { pagerState.animateScrollToPage(0) }
                                }
                            ) {
                                Text(stringResource(R.string.label_comments), modifier = Modifier.padding(16.dp))
                            }
                            Tab(
                                selected = pagerState.currentPage == 1,
                                onClick = {
                                    coroutineScope.launch { pagerState.animateScrollToPage(1) }
                                }
                            ) {
                                Text(stringResource(R.string.label_related_videos), modifier = Modifier.padding(16.dp))
                            }
                            Tab(
                                selected = pagerState.currentPage == 2,
                                onClick = {
                                    coroutineScope.launch { pagerState.animateScrollToPage(2) }
                                }
                            ) {
                                Text(stringResource(R.string.label_description), modifier = Modifier.padding(16.dp))
                            }
                        }

                        // 3. The Pager that enables swiping
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.Top // Ensures content starts at top
                        ) { page ->
                            when (page) {
                                0 -> CommentsSection(comments)
                                1 -> RelatedVideosSection(backStack, ypvm, relatedVideos)
                                2 -> DescriptionSection(videoData.description)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetails(
    backStack: NavBackStack<Route>,
    ypvm: YouPipeViewModel,
    videoData: VideoData,
    videoID: Long,
    videoStreams: List<VideoStream>,
    audioStreams: List<AudioStream>
) {
    val context = LocalContext.current
    val downloadedFlow = remember(videoID) { ypvm.downloadedById(videoID) }
    val downloadedVideo by downloadedFlow.collectAsState(initial = null)
    val activeDownloads by DownloadManager.activeDownloads.collectAsState()
    val downloadProgress = activeDownloads[videoID]?.progress

    var isDownloadDialogVisible by remember { mutableStateOf(false) }

    if (isDownloadDialogVisible) {
        var selectedVideoStream by remember { mutableStateOf(videoStreams.maxByOrNull { it.height } ?: videoStreams.first()) }
        var selectedAudioStream by remember { mutableStateOf(audioStreams.firstOrNull()) }

        val languages = remember(audioStreams) { audioStreams.map { it.language }.distinct().sorted() }
        var selectedLanguage by remember { mutableStateOf(selectedAudioStream?.language ?: languages.firstOrNull() ?: "Default") }
        val filteredAudioStreams = remember(selectedLanguage, audioStreams) { audioStreams.filter { it.language == selectedLanguage } }

        var videoExpanded by remember { mutableStateOf(false) }
        var languageExpanded by remember { mutableStateOf(false) }
        var audioExpanded by remember { mutableStateOf(false) }

        Dialog(onDismissRequest = { isDownloadDialogVisible = false }) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Download Options", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    
                    Text("Resolution", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = videoExpanded,
                        onExpandedChange = { videoExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = "${selectedVideoStream.quality} (${getVideoCodecName(selectedVideoStream.codec)}) - ${formatSize(selectedVideoStream.size)}",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = videoExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = videoExpanded,
                            onDismissRequest = { videoExpanded = false }
                        ) {
                            videoStreams.forEach { stream ->
                                DropdownMenuItem(
                                    text = { Text("${stream.quality} (${getVideoCodecName(stream.codec)}) - ${formatSize(stream.size)}") },
                                    onClick = {
                                        selectedVideoStream = stream
                                        videoExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    if (languages.size > 1) {
                        Spacer(Modifier.height(16.dp))
                        Text("Language", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        ExposedDropdownMenuBox(
                            expanded = languageExpanded,
                            onExpandedChange = { languageExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedLanguage,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = languageExpanded,
                                onDismissRequest = { languageExpanded = false }
                            ) {
                                languages.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(lang) },
                                        onClick = {
                                            selectedLanguage = lang
                                            selectedAudioStream = audioStreams.firstOrNull { it.language == lang }
                                            languageExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (filteredAudioStreams.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("Audio Bitrate", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        ExposedDropdownMenuBox(
                            expanded = audioExpanded,
                            onExpandedChange = { audioExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedAudioStream?.let { "${it.bitrate / 1000} kbps (${getAudioCodecName(it.codec)}) - ${formatSize(it.size)}" } ?: "None",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = audioExpanded) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = audioExpanded,
                                onDismissRequest = { audioExpanded = false }
                            ) {
                                filteredAudioStreams.forEach { stream ->
                                    DropdownMenuItem(
                                        text = { Text("${stream.bitrate / 1000} kbps (${getAudioCodecName(stream.codec)}) - ${formatSize(stream.size)}") },
                                        onClick = {
                                            selectedAudioStream = stream
                                            audioExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { isDownloadDialogVisible = false }) { Text("Cancel") }
                        TextButton(onClick = {
                            isDownloadDialogVisible = false
                            val videoInfo = VideoInfo(videoData.title, videoID, videoData.duration, videoData.views, videoData.uploadDate, videoData.thumbnailURL, videoData.author)
                            DownloadManager.enqueueDownload(context, videoInfo, selectedVideoStream.url, selectedAudioStream?.url)
                        }) { Text("Download") }
                    }
                }
            }
        }
    }

    Column {
        ListItem({
            Text(videoData.title, style = MaterialTheme.typography.titleMedium)
        }, Modifier, {}, {
            Text(stringResource(R.string.video_info_format, videoData.author, countString(context, videoData.views), uploadTimeAgo(context, videoData.uploadDate)))
        }, {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(videoData.authorThumbnail)
                    .memoryCacheKey("author-thumb-${videoData.authorURL}")
                    .build(),
                contentDescription = null,
                Modifier.size(32.dp).clip(CircleShape).clickable{
                    backStack.add(Route.ChannelPage(videoData.authorURL))
                }
            )
        }, {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloadProgress != null) {
                    CircularProgressIndicator(
                        progress = { downloadProgress.toFloat() },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    IconButton(onClick = {
                        DownloadManager.cancelDownload(context, videoID)
                    }) {
                        Icon(
                            painterResource(com.vayunmathur.library.R.drawable.close_24px),
                            contentDescription = "Cancel Download"
                        )
                    }
                } else if (downloadedVideo == null) {
                    IconButton(onClick = {
                        isDownloadDialogVisible = true
                    }) {
                        Icon(
                            painterResource(R.drawable.download_24px),
                            contentDescription = "Download"
                        )
                    }
                } else {
                    IconButton(onClick = {
                        ypvm.deleteDownloadedVideo(downloadedVideo!!)
                    }) {
                        Icon(
                            painterResource(com.vayunmathur.library.R.drawable.delete_24px),
                            contentDescription = "Delete Download",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        })
    }
}

@Composable
fun DescriptionSection(description: String) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun RelatedVideosSection(backStack: NavBackStack<Route>, ypvm: YouPipeViewModel, relatedVideos: List<VideoInfo>) {
    LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(relatedVideos, { it.videoID }) {
            VideoItem(backStack, ypvm, it, true)
        }
    }
}

@Composable
fun CommentsSection(comments: List<Comment>) {
    LazyColumn {
        items(comments, key = { "${it.author}|${it.text.hashCode()}" }) {
            CommentItem(it)
        }
    }
}

@Composable
fun CommentItem(c: Comment) {
    ListItem({
        Text(c.text)
    }, Modifier, {
        Text(c.author)
    }, {
        Column {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.outline_thumb_up_24), null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(c.likes.toString())
                Spacer(Modifier.width(16.dp))
                Icon(painterResource(R.drawable.outline_thumb_down_24), null, Modifier.size(16.dp))
            }
        }
    })
}

fun uploadTimeAgo(context: android.content.Context, date: Instant): String {
    val now = Clock.System.now()
    return when(val duration = now - date) {
        in 0.minutes..5.minutes -> context.getString(R.string.time_ago_just_now)
        in 5.minutes..1.hours -> context.getString(R.string.time_ago_minutes, duration.inWholeMinutes.toInt())
        in 1.hours..24.hours -> context.getString(R.string.time_ago_hours, duration.inWholeHours.toInt())
        else -> uploadTimeAgo(context, date.toLocalDateTime(TimeZone.currentSystemDefault()).date)
    }
}

fun uploadTimeAgo(context: android.content.Context, date: LocalDate): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val period = date.periodUntil(now.date)
    return if(period.years > 0) {
        context.getString(R.string.time_ago_years, period.years)
    } else if(period.months > 0) {
        context.getString(R.string.time_ago_months, period.months)
    } else if(period.days > 0) {
        context.getString(R.string.time_ago_days, period.days)
    } else {
        throw IllegalStateException("Should have been found by uploadTimeAgo")
    }
}

fun countString(context: android.content.Context, count: Long): String {
    val digits = count.toString().length
    return when(digits) {
        in 0..3 -> count.toString()
        4 -> context.getString(R.string.count_k_format, (count / 1000.0).round(2).toString())
        5 -> context.getString(R.string.count_k_format, (count / 1000.0).round(1).toString())
        6 -> context.getString(R.string.count_k_format, (count / 1000).toString())
        7 -> context.getString(R.string.count_m_format, (count / 1000000.0).round(2).toString())
        8 -> context.getString(R.string.count_m_format, (count / 1000000.0).round(1).toString())
        9 -> context.getString(R.string.count_m_format, (count / 1000000).toString())
        10 -> context.getString(R.string.count_b_format, (count / 1000000000.0).round(2).toString())
        11 -> context.getString(R.string.count_b_format, (count / 1000000000.0).round(1).toString())
        12 -> context.getString(R.string.count_b_format, (count / 1000000000).toString())
        else -> count.toString()
    }
}

fun String.fromHTML(): String {
    return HtmlCompat.fromHtml(
        this.replace("<br>", "\n"),
        HtmlCompat.FROM_HTML_MODE_LEGACY
    ).toString()
}

fun getVideoCodecName(codec: String): String {
    return when {
        codec.contains("av01", ignoreCase = true) -> "av1"
        codec.contains("vp9", ignoreCase = true) || codec.contains("vp09", ignoreCase = true) -> "vp9"
        codec.contains("avc", ignoreCase = true) || codec.contains("h264", ignoreCase = true) -> "avc"
        else -> codec
    }
}

fun getAudioCodecName(codec: String): String {
    return when {
        codec.contains("opus", ignoreCase = true) -> "opus"
        codec.contains("mp4a", ignoreCase = true) || codec.contains("aac", ignoreCase = true) -> "aac"
        else -> codec
    }
}

fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes > 0 -> "$bytes B"
        else -> "Unknown"
    }
}
