package com.vayunmathur.youpipe.ui

import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavBackStack
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.videoURLtoID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

interface ItemInfo
data class ChannelInfo(val name: String, val url: String, val subscribers: Long, val videos: Int, val avatar: String): ItemInfo
data class VideoInfo(val name: String, val videoID: Long, val views: Long, val uploadDate: Instant, val thumbnailURL: String, val author: String): ItemInfo

@Composable
fun ChannelPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, url: String) {
    println(url)
    var videos by remember { mutableStateOf<List<VideoInfo>>(listOf()) }
    var channelInfo by remember { mutableStateOf<ChannelInfo?>(null) }

    val subscriptions by viewModel.data<Subscription>().collectAsState()

    LaunchedEffect(Unit) {
        val youtubeService: StreamingService = ServiceList.YouTube
        withContext(Dispatchers.IO) {
            val channelExtractor = youtubeService.getChannelExtractor(url)
            channelExtractor.fetchPage()
            val feedExtractor = youtubeService.getFeedExtractor(url)!!
            feedExtractor.fetchPage()
            videos = feedExtractor.initialPage.items.map {
                VideoInfo(
                    it.name,
                    videoURLtoID(it.url),
                    it.viewCount,
                    it.uploadDate!!.instant.toKotlinInstant(),
                    it.thumbnails.first().url,
                    channelExtractor.name
                )
            }

            channelInfo = ChannelInfo(
                channelExtractor.name,
                channelExtractor.url,
                channelExtractor.subscriberCount,
                videos.size,
                channelExtractor.avatars.first().url
            )
        }
    }

    Scaffold() { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            channelInfo?.let {
                ChannelHeader(it)
                val existingSubscription = subscriptions.firstOrNull { it.url == url }
                if(existingSubscription == null) {
                    Button({
                        viewModel.upsert(Subscription(name = it.name, url = url, avatarURL = it.avatar))
                    }, Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Text("Subscribe")
                    }
                } else {
                    OutlinedButton({
                        viewModel.delete(existingSubscription)
                    }, Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Text("Unsubscribe")
                    }
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
            }
            LazyColumn() {
                items(videos, {it.videoID}) {
                    VideoItem(backStack, it, false)
                }
            }
        }
    }
}

@Composable
fun VideoItem(backStack: NavBackStack<Route>, videoInfo: VideoInfo, showAuthor: Boolean) {
    Row(Modifier.invisibleClickable{
        backStack.add(Route.VideoPage(videoInfo.videoID))
    }) {
        Box(Modifier.weight(1f)) {
            Box(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp).clip(RoundedCornerShape(12.dp))) {
                AsyncImage(
                    model = videoInfo.thumbnailURL,
                    contentDescription = null,
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
            }
        }
        Box(Modifier.weight(1.5f)) {
            ListItem({
                Text(videoInfo.name, style = MaterialTheme.typography.titleMedium)
            }, Modifier, {

            }, {
                Column() {
                    if(showAuthor) {
                        Text(videoInfo.author, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "${countString(videoInfo.views)} views | ${uploadTimeAgo(videoInfo.uploadDate)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            })
        }
    }
}

@Composable
fun ChannelHeader(channelInfo: ChannelInfo) {
    ListItem({
        Text(channelInfo.name, style = MaterialTheme.typography.titleLarge)
    }, Modifier, {

    }, {
        Text("${countString(channelInfo.subscribers)} subscribers | ${channelInfo.videos} videos")
    }, {
        AsyncImage(
            model = channelInfo.avatar,
            contentDescription = null,
            Modifier.size(52.dp).clip(CircleShape)
        )
    })
}