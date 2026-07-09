package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.decodeHtml
import kotlinx.serialization.Serializable
import kotlin.time.Instant

interface ItemInfo
@Serializable
data class ChannelInfo(val name: String, val channelID: String, val subscribers: Long, val videos: Int, val avatar: String): ItemInfo {
    fun toSubscription(): Subscription {
        return Subscription(name = name, channelID = channelID, avatarURL = avatar)
    }
}

@Serializable
data class VideoInfo(val name: String, val videoID: Long, val duration: Long, val views: Long, val uploadDate: Instant, val thumbnailURL: String, val author: String): ItemInfo

@Composable
fun ChannelPage(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
    channelID: String,
) {
    val channelState by youPipeViewModel.channelState.collectAsState()
    val videos = channelState.videos
    val channelInfo = channelState.info

    val subscriptions by youPipeViewModel.subscriptions.collectAsState()

    LaunchedEffect(channelID) {
        youPipeViewModel.loadChannel(channelID)
    }

    Scaffold { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            channelInfo?.let { info ->
                ChannelHeader(info)
                val existingSubscription = subscriptions.firstOrNull { it.channelID == info.channelID }
                if(existingSubscription == null) {
                    Button({
                        youPipeViewModel.upsertSubscription(Subscription(name = info.name, channelID = info.channelID, avatarURL = info.avatar))
                    }, Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.action_subscribe))
                    }
                } else {
                    OutlinedButton({
                        youPipeViewModel.deleteSubscription(existingSubscription)
                    }, Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.action_unsubscribe))
                    }
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
            }
            LazyColumn {
                items(videos, {it.videoID}) {
                    VideoItem(backStack, youPipeViewModel, it, false)
                }
            }
        }
    }
}

@Composable
fun VideoItem(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
    videoInfo: VideoInfo,
    showAuthor: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    backupOnClick: Boolean = true,
    trailingContent: @Composable (() -> Unit)? = null,
    reason: String? = null,
    overflowActions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val context = LocalContext.current
    val historyFlow = remember(videoInfo.videoID) { youPipeViewModel.historyById(videoInfo.videoID) }
    val historyItem by historyFlow.collectAsState(initial = null)
    val timeWatched = historyItem?.progress ?: 0
    val percentWatched = if (videoInfo.duration > 0) timeWatched.toDouble() / videoInfo.duration.toDouble() else 0.0

    val deArrowEnabled by youPipeViewModel.deArrowEnabled.collectAsState()
    val deArrowCache by youPipeViewModel.deArrowCache.collectAsState()
    val deArrowData = if (deArrowEnabled) deArrowCache[videoInfo.videoID] else null
    val displayTitle = deArrowData?.title ?: videoInfo.name
    val displayThumbnail = deArrowData?.thumbnailUrl ?: videoInfo.thumbnailURL
    
    val itemModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else if(backupOnClick) {
        modifier.invisibleClickable {
            backStack.add(Route.VideoPage(videoInfo.videoID))
        }
    } else {
        modifier
    }

    val effectiveTrailing: (@Composable () -> Unit)? = when {
        overflowActions.isNotEmpty() -> {
            { VideoOverflowMenu(overflowActions) }
        }
        else -> trailingContent
    }

    Row(itemModifier) {
        Box(Modifier.weight(1f)) {
            Box(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp).clip(RoundedCornerShape(12.dp))) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(displayThumbnail)
                        .memoryCacheKey("video-thumb-${videoInfo.videoID}-${if (deArrowData?.thumbnailUrl != null) "da" else "yt"}")
                        .build(),
                    contentDescription = null,
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))) {
                    if(percentWatched > 0)
                        Surface(Modifier.weight(percentWatched.toFloat()).height(6.dp), color = Color.Red.copy(alpha = 0.6f)) {}
                    if(percentWatched < 1f)
                        Surface(Modifier.weight(1f-percentWatched.toFloat()).height(6.dp), color = Color.Black.copy(alpha = 0.8f)) {}
                }
            }
        }
        Box(Modifier.weight(1.5f)) {
            ListItem({
                Text(displayTitle.decodeHtml(), style = MaterialTheme.typography.titleMedium)
            }, Modifier, {

            }, {
                Column {
                    if(showAuthor) {
                        Text(videoInfo.author.decodeHtml(), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        stringResource(R.string.video_stat_format, countString(context, videoInfo.views), uploadTimeAgo(context, videoInfo.uploadDate)),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (reason != null) {
                        Text(
                            stringResource(R.string.recommendation_reason, reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }, trailingContent = effectiveTrailing?.let { { it() } }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        }
    }
}

@Composable
private fun VideoOverflowMenu(actions: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.outline_more_vert_24),
                contentDescription = stringResource(R.string.action_more_options),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { (label, onClick) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onClick()
                    },
                )
            }
        }
    }
}

@Composable
fun ChannelHeader(channelInfo: ChannelInfo) {
    val context = LocalContext.current
    ListItem({
        Text(channelInfo.name.decodeHtml(), style = MaterialTheme.typography.titleLarge)
    }, Modifier, {

    }, {
        Text(stringResource(R.string.channel_info, countString(context, channelInfo.subscribers)))
    }, {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(channelInfo.avatar)
                .memoryCacheKey("channel-avatar-${channelInfo.channelID}")
                .build(),
            contentDescription = null,
            Modifier.size(52.dp).clip(CircleShape)
        )
    })
}
