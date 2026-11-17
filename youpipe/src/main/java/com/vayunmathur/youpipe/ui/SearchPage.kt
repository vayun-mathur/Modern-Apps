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

/**
 * Home: the recommendation feed, with search living in the top app bar.
 *
 * Binder only — it turns the ViewModel's flows into a [SearchUiState] and hands that to
 * [SearchScreen], which is what the store listing preview renders.
 */
@Composable
fun SearchPage(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
) {
    val searchQuery by youPipeViewModel.searchQuery.collectAsState()
    val suggestions by youPipeViewModel.suggestions.collectAsState()
    val searchResults by youPipeViewModel.searchResults.collectAsState()
    val recommendations by youPipeViewModel.recommendations.collectAsState()
    val recommendationsLoading by youPipeViewModel.recommendationsLoading.collectAsState()

    // Resume bars used to come from a per-row flow. One flow over the whole history table
    // gets the same numbers while letting a row stay a plain value.
    val history by youPipeViewModel.historyVideos.collectAsState()
    val deArrowEnabled by youPipeViewModel.deArrowEnabled.collectAsState()
    val deArrowCache by youPipeViewModel.deArrowCache.collectAsState()
    val progressById = remember(history) { history.associate { it.id to it.progress } }
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(Unit) {
        youPipeViewModel.loadRecommendations()
    }

    fun rowFor(video: VideoInfo, reason: String? = null): VideoRowState {
        val deArrow = if (deArrowEnabled) deArrowCache[video.videoID] else null
        val watched = progressById[video.videoID] ?: 0L
        return videoRowState(
            context = context,
            videoInfo = video,
            showAuthor = true,
            reason = reason,
            percentWatched = if (video.duration > 0) (watched.toDouble() / video.duration).toFloat() else 0f,
            deArrowTitle = deArrow?.title,
            deArrowThumbnailURL = deArrow?.thumbnailUrl,
        )
    }

    SearchScreen(
        state = SearchUiState(
            query = searchQuery,
            suggestions = suggestions,
            results = searchResults.mapNotNull { item ->
                when (item) {
                    is VideoInfo -> SearchResultRow.Video(rowFor(item))
                    is ChannelInfo -> SearchResultRow.Channel(
                        channelID = item.channelID,
                        name = item.name,
                        avatarURL = item.avatar,
                        subscribers = resources.getString(
                            R.string.subscribers_count,
                            countString(context, item.subscribers),
                        ),
                    )
                    else -> null
                }
            },
            recommendations = recommendations.map { rowFor(it.video, it.reason) },
            recommendationsLoading = recommendationsLoading,
        ),
        actions = object : SearchActions {
            override fun setSearchQuery(query: String) = youPipeViewModel.setSearchQuery(query)

            override fun submitSearch(): Boolean {
                val watchID = youPipeViewModel.resolveWatchUrl()
                if (watchID == null) {
                    youPipeViewModel.performSearch()
                    return false
                }
                backStack.add(Route.VideoPage(watchID))
                return true
            }

            override fun openVideo(videoID: Long) {
                backStack.add(Route.VideoPage(videoID))
            }

            override fun openChannel(channelID: String) {
                backStack.add(Route.ChannelPage(channelID))
            }

            override fun notInterested(channelKey: String) =
                youPipeViewModel.removeInterest(channelKey = channelKey)

            override fun moreLikeThis(channelKey: String) = youPipeViewModel.boostChannel(channelKey)

            override fun pinChannel(channelKey: String) = youPipeViewModel.pinChannel(channelKey)

            override fun blockChannel(channelKey: String) = youPipeViewModel.blockChannel(channelKey)
        },
    )
}
