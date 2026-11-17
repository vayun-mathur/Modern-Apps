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


/**
 * The channel's identity block, and the far end of the morph that starts at a subscription row.
 *
 * Keyed on the container rather than on the avatar alone: an avatar gliding out of a row while the
 * name beside it cuts reads worse than no morph at all. The avatar is nested inside so its pixels
 * keep their identity across the size change - 24dp in the row, 52dp here.
 */
@Composable
fun ChannelHeader(channelInfo: ChannelInfo) {
    val context = LocalContext.current
    ListItem(modifier = Modifier.sharedContainer("youpipe-channel-${channelInfo.channelID}"), overlineContent = {

    }, supportingContent = {
        Text(stringResource(R.string.subscribers_count, countString(context, channelInfo.subscribers)))
    }, leadingContent = {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(channelInfo.avatar)
                .memoryCacheKey("channel-avatar-${channelInfo.channelID}")
                .build(),
            contentDescription = null,
            Modifier.sharedContent("youpipe-channel-avatar-${channelInfo.channelID}")
                .size(52.dp)
                .clip(CircleShape)
        )
    }) {
        Text(channelInfo.name.decodeHtml(), style = MaterialTheme.typography.titleLarge)
    }
}
