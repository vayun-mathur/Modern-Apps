package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavBackStack
import coil.compose.AsyncImage
import com.vayunmathur.library.util.round
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.videoIDtoURL
import com.vayunmathur.youpipe.videoURLtoID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.periodUntil
import kotlinx.datetime.toLocalDateTime
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

data class VideoChapter(val time: Int, val title: String, val previewURL: String?)
data class AudioStream(val url: String, val bitrate: Int)
data class VideoStream(val url: String, val width: Int, val height: Int, val bitrate: Int, val fps: Int, val quality: String)
data class VideoData(val title: String, val views: Long, val uploadDate: Instant, val thumbnailURL: String, val author: String, val authorURL: String, val authorThumbnail: String)
data class Comment(val text: String, val author: String, val likes: Int, val dislikes: Int)

@Composable
fun VideoPage(backStack: NavBackStack<Route>, videoID: Long) {
    val url = videoIDtoURL(videoID)

    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var relatedVideos by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var videoData by remember { mutableStateOf<VideoData?>(null) }
    var videoStreams by remember { mutableStateOf<List<VideoStream>>(listOf()) }
    var audioStreams by remember { mutableStateOf<List<AudioStream>>(listOf()) }
    var segments by remember { mutableStateOf<List<VideoChapter>>(listOf()) }

    LaunchedEffect(Unit) {
        val youtubeService: StreamingService = ServiceList.YouTube
        withContext(Dispatchers.IO) {
            val streamExtractor = youtubeService.getStreamExtractor(url)
            streamExtractor.fetchPage()
            segments = streamExtractor.streamSegments.map { VideoChapter(it.startTimeSeconds*1000, it.title, it.previewUrl) }
            videoStreams = streamExtractor.videoOnlyStreams.map { VideoStream(it.content, it.width, it.height, it.bitrate, it.fps, it.quality) }
            audioStreams = streamExtractor.audioStreams.map { AudioStream(it.content, it.bitrate) }
            videoData = VideoData(streamExtractor.name, streamExtractor.viewCount, streamExtractor.uploadDate!!.instant.toKotlinInstant(), streamExtractor.thumbnails.first().url, streamExtractor.uploaderName, streamExtractor.uploaderUrl, streamExtractor.uploaderAvatars.first().url)
            val relatedVideosEx = streamExtractor.relatedItems ?: return@withContext
            relatedVideos = relatedVideosEx.items.filterIsInstance<StreamInfoItem>().map {
                VideoInfo(it.name, videoURLtoID(it.url), it.viewCount, it.uploadDate!!.instant.toKotlinInstant(), it.thumbnails.first().url, it.uploaderName)
            }
        }
        withContext(Dispatchers.IO) {
            val commentsEx = youtubeService.getCommentsExtractor(url)
            commentsEx.fetchPage()
            comments = commentsEx.initialPage.items.map {
                val content = if(it.commentText.type == Description.HTML) {
                    it.commentText.content.fromHTML()
                } else {
                    it.commentText.content
                }
                Comment(content, it.uploaderName, it.likeCount, 0)
            }
        }
    }

    Scaffold() { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            videoData?.let {
                VideoPlayer(videoStreams.first(), audioStreams.first(), videoStreams, audioStreams, segments, it.title, it.author)
                VideoDetails(backStack, it)
            }

            val pagerState = rememberPagerState(pageCount = { 2 })
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
                        Text("Comments", modifier = Modifier.padding(16.dp))
                    }
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                        }
                    ) {
                        Text("Related Videos", modifier = Modifier.padding(16.dp))
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
                        1 -> RelatedVideosSection(backStack, relatedVideos)
                    }
                }
            }
        }
    }
}

@Composable
fun VideoDetails(backStack: NavBackStack<Route>, videoData: VideoData) {
    ListItem({
        Text(videoData.title, style = MaterialTheme.typography.titleMedium)
    }, Modifier, {}, {
        Text("${videoData.author} | ${countString(videoData.views)} views | ${uploadTimeAgo(videoData.uploadDate)}")
    }, {
        AsyncImage(
            model = videoData.authorThumbnail,
            contentDescription = null,
            Modifier.size(32.dp).clip(CircleShape).clickable{
                backStack.add(Route.ChannelPage(videoData.authorURL))
            }
        )
    })
}

@Composable
fun RelatedVideosSection(backStack: NavBackStack<Route>, relatedVideos: List<VideoInfo>) {
    LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(relatedVideos, { it.videoID }) {
            VideoItem(backStack, it, true)
        }
    }
}

@Composable
fun CommentsSection(comments: List<Comment>) {
    LazyColumn {
        items(comments) {
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

fun uploadTimeAgo(date: Instant): String {
    val now = Clock.System.now()
    return when(val duration = now - date) {
        in 0.minutes..5.minutes -> "Just now"
        in 5.minutes..1.hours -> "${duration.inWholeMinutes} minutes ago"
        in 1.hours..24.hours -> "${duration.inWholeHours} hours ago"
        else -> uploadTimeAgo(date.toLocalDateTime(TimeZone.currentSystemDefault()).date)
    }
}

fun uploadTimeAgo(date: LocalDate): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val period = date.periodUntil(now.date)
    return if(period.years > 0) {
        "${period.years} years ago"
    } else if(period.months > 0) {
        "${period.months} months ago"
    } else if(period.days > 0) {
        "${period.days} days ago"
    } else {
        throw IllegalStateException("Should have been found by uploadTimeAgo")
    }
}

fun countString(count: Long): String {
    val digits = count.toString().length
    return when(digits) {
        in 0..3 -> count.toString()
        4 -> "${(count / 1000.0).round(2)}K"
        5 -> "${(count / 1000.0).round(1)}K"
        6 -> "${(count / 1000)}K"
        7 -> "${(count / 1000000.0).round(2)}M"
        8 -> "${(count / 1000000.0).round(1)}M"
        9 -> "${(count / 1000000)}M"
        10 -> "${(count / 1000000000.0).round(2)}B"
        11 -> "${(count / 1000000000.0).round(1)}B"
        12 -> "${(count / 1000000000)}B"
        else -> "$count"
    }
}

fun String.fromHTML(): String {
    return this.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("<br>", "\n")
}