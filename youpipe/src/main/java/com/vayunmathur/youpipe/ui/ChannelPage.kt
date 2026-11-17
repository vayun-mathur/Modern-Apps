package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.adaptiveGridCells
import com.vayunmathur.library.ui.isExpandedWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedContent
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.platform.VideoRowState
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.network.decodeHtml
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Contract for item info. */
interface ItemInfo
/** Immutable value describing channel info; carries name, channelID, subscribers, videos, avatar. */
@Serializable
data class ChannelInfo(val name: String, val channelID: String, val subscribers: Long, val videos: Int, val avatar: String): ItemInfo {
    fun toSubscription(): Subscription {
        return Subscription(name = name, channelID = channelID, avatarURL = avatar)
    }
}

/** Immutable value describing video info; carries name, videoID, duration, views, uploadDate, thumbnailURL, author, isPaid. */
@Serializable
data class VideoInfo(val name: String, val videoID: Long, val duration: Long, val views: Long, val uploadDate: Instant, val thumbnailURL: String, val author: String, val isPaid: Boolean = false): ItemInfo

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

    // RAW SCAFFOLD EXCEPTION: the header (identity + subscribe button) scrolls with the
    // feed on compact windows but pins above an adaptive video grid on expanded widths.
    // No shared scaffold models a collapsing header over a column/grid switch.
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
            if (isExpandedWidth()) {
                LazyVerticalGrid(
                    columns = adaptiveGridCells(320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(videos, { it.videoID }) {
                        VideoItem(backStack, youPipeViewModel, it, false, vertical = true)
                    }
                }
            } else {
                LazyColumn {
                    items(videos, { it.videoID }) {
                        VideoItem(backStack, youPipeViewModel, it, false)
                    }
                }
            }
        }
    }
}
