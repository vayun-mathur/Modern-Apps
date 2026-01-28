package com.vayunmathur.youpipe.ui

import android.os.Looper.prepare
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.parseAsHtml
import androidx.core.util.rangeTo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.navigation3.runtime.NavBackStack
import coil.compose.AsyncImage
import com.vayunmathur.library.util.round
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.periodUntil
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toLocalDateTime
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.time.temporal.ChronoUnit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

data class AudioStream(val url: String, val bitrate: Int)
data class VideoStream(val url: String, val width: Int, val height: Int, val bitrate: Int, val fps: Int, val quality: String)
data class VideoData(val title: String, val views: Long, val uploadDate: Instant, val thumbnailURL: String, val author: String)
data class Comment(val text: String, val author: String, val likes: Int, val dislikes: Int)
data class RelatedVideo(val url: String, var thumbnailURL: String, val name: String, val author: String, val views: Long, val textUploadDate: String)

@Composable
fun VideoPage(backStack: NavBackStack<Route>, url: String) {

    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var relatedVideos by remember { mutableStateOf<List<RelatedVideo>>(emptyList()) }
    var videoData by remember { mutableStateOf<VideoData?>(null) }
    var videoStreams by remember { mutableStateOf<List<VideoStream>>(listOf()) }
    var audioStreams by remember { mutableStateOf<List<AudioStream>>(listOf()) }

    LaunchedEffect(Unit) {
        val youtubeService: StreamingService = ServiceList.YouTube
        withContext(Dispatchers.IO) {
            val streamExtractor = youtubeService.getStreamExtractor(url)
            streamExtractor.fetchPage()
            streamExtractor.videoOnlyStreams.map {
                println("${it.width}, ${it.height}, ${it.content}, ${it.deliveryMethod}, ${it.bitrate}, ${it.fps}, ${it.quality}")
            }
            videoStreams = streamExtractor.videoOnlyStreams.map { VideoStream(it.content, it.width, it.height, it.bitrate, it.fps, it.quality) }
            audioStreams = streamExtractor.audioStreams.map { AudioStream(it.content, it.bitrate) }
            videoData = VideoData(streamExtractor.name, streamExtractor.viewCount, streamExtractor.uploadDate!!.instant.toKotlinInstant(), streamExtractor.thumbnails.first().url, streamExtractor.uploaderName)
            val relatedVideosEx = streamExtractor.relatedItems ?: return@withContext
            relatedVideos = relatedVideosEx.items.filterIsInstance<StreamInfoItem>().map {
                RelatedVideo(it.url, it.thumbnails.first().url, it.name, it.uploaderName, it.viewCount, it.textualUploadDate!!)
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
                VideoPlayer(videoStreams.first(), audioStreams.first(), videoStreams, audioStreams)
                VideoDetails(it)
            }
            //RelatedVideosSection(backStack, relatedVideos)
            CommentsSection(comments)
        }
    }
}

@Composable
fun VideoDetails(videoData: VideoData) {
    ListItem({
        Text(videoData.title, style = MaterialTheme.typography.titleMedium)
    }, Modifier, {}, {
        Text("${videoData.author} | ${countString(videoData.views)} views | ${uploadTimeAgo(videoData.uploadDate)}")
    })
}

@Composable
fun RelatedVideosSection(backStack: NavBackStack<Route>, relatedVideos: List<RelatedVideo>) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(relatedVideos, { it.url }) {
            RelatedVideoItem(backStack, it)
        }
    }
}

@Composable
fun RelatedVideoItem(backStack: NavBackStack<Route>, r: RelatedVideo) {
    Card {
        Column {
            AsyncImage(
                model = r.thumbnailURL,
                contentDescription = null,
                Modifier.fillMaxWidth()
                    .aspectRatio(16f / 9f),
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
            )
            ListItem({
                Text(r.name, style = MaterialTheme.typography.titleMedium)
            }, Modifier.clickable {
                backStack.add(Route.VideoPage(r.url))
            }, {}, {
                Text("${r.author} | ${countString(r.views)} views | ${r.textUploadDate}")
            }, colors = ListItemDefaults.colors(Color.Transparent))
        }
    }
}

@Composable
fun CommentsSection(comments: List<Comment>) {
    Column() {
        comments.forEach {
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