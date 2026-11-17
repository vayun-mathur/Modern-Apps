package com.vayunmathur.youpipe.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.SearchBar
import androidx.compose.material3.rememberSearchBarState
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SearchBarDefaults
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.isExpandedWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.SearchActions
import com.vayunmathur.youpipe.platform.SearchResultRow
import com.vayunmathur.youpipe.platform.SearchUiState
import com.vayunmathur.youpipe.platform.VideoRowState
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.resolveWatchUrl
import com.vayunmathur.youpipe.platform.blockChannel
import com.vayunmathur.youpipe.platform.boostChannel
import com.vayunmathur.youpipe.platform.loadRecommendations
import com.vayunmathur.youpipe.platform.pinChannel
import com.vayunmathur.youpipe.platform.removeInterest
import kotlinx.coroutines.launch


@Composable
fun ChannelRow(channel: SearchResultRow.Channel, onClick: () -> Unit) {
    ListItem(modifier = Modifier.clickable(onClick = onClick), overlineContent = {

    }, supportingContent = {
        Text(channel.subscribers)
    }, leadingContent = {
        Box(
            Modifier.size(50.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (channel.avatarURL.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(channel.avatarURL)
                        .memoryCacheKey("channel-avatar-${channel.channelID}")
                        .build(),
                    contentDescription = null,
                    Modifier.fillMaxSize()
                )
            }
        }
    }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) {
        Text(channel.name, style = MaterialTheme.typography.titleMedium)
    }
}
