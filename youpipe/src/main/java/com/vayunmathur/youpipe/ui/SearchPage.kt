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
import com.vayunmathur.youpipe.util.SearchActions
import com.vayunmathur.youpipe.util.SearchResultRow
import com.vayunmathur.youpipe.util.SearchUiState
import com.vayunmathur.youpipe.util.VideoRowState
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.resolveWatchUrl
import com.vayunmathur.youpipe.util.blockChannel
import com.vayunmathur.youpipe.util.boostChannel
import com.vayunmathur.youpipe.util.loadRecommendations
import com.vayunmathur.youpipe.util.pinChannel
import com.vayunmathur.youpipe.util.removeInterest
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

/**
 * Stateless home screen.
 *
 * Everything the user taps inside the screen goes through [actions]. A preview can hand it a
 * freshly-remembered stack.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    actions: SearchActions,
) {
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState(initialText = state.query)
    val scope = rememberCoroutineScope()

    fun collapseSearch() {
        scope.launch { searchBarState.animateToCollapsed() }
    }

    // Text field -> ViewModel: forward edits. Keyed on the query too so the
    // comparison never goes stale; the guard drops the initial emission.
    LaunchedEffect(textFieldState, state.query) {
        snapshotFlow { textFieldState.text.toString() }.collect { text ->
            if (text != state.query) actions.setSearchQuery(text)
        }
    }
    // ViewModel -> text field: reflect external query changes.
    LaunchedEffect(state.query) {
        if (textFieldState.text.toString() != state.query) {
            textFieldState.setTextAndPlaceCursorAtEnd(state.query)
        }
    }

    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = { query ->
                // The field already holds `query`; make sure the ViewModel sees the
                // exact submitted text before submit reads it.
                actions.setSearchQuery(query)
                // Only collapse when the submit actually navigated somewhere — a
                // plain search has to leave the overlay up to show its results.
                if (actions.submitSearch()) collapseSearch()
            },
            placeholder = { Text(stringResource(UiR.string.search)) },
        )
    }

    // RAW SCAFFOLD EXCEPTION: the top chrome is a Material3 SearchBar (not a
    // TopAppBar) whose expanded state overlays the whole screen with search
    // results/suggestions, and the body is a 3-way branch (loading spinner /
    // empty state / recommendation feed). No shared scaffold models a SearchBar
    // app bar plus this branching content.
    Scaffold(
        topBar = {
            SearchBar(
                state = searchBarState,
                inputField = inputField,
                modifier = Modifier.statusBarsPadding().fillMaxWidth(),
            )
            ExpandedFullScreenSearchBar(
                state = searchBarState,
                inputField = inputField,
            ) {
                LazyColumn {
                    if (state.results.isNotEmpty()) {
                        items(state.results, key = {
                            when (it) {
                                is SearchResultRow.Video -> "v-${it.video.videoID}"
                                is SearchResultRow.Channel -> "c-${it.channelID}"
                            }
                        }) { item ->
                            when (item) {
                                is SearchResultRow.Video -> VideoRow(
                                    row = item.video,
                                    modifier = Modifier.clickable {
                                        collapseSearch()
                                        actions.openVideo(item.video.videoID)
                                    },
                                )
                                is SearchResultRow.Channel -> ChannelRow(item) {
                                    actions.openChannel(item.channelID)
                                }
                            }
                        }
                    } else {
                        items(state.suggestions, key = { it }) { suggestion ->
                            Text(
                                text = suggestion,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        textFieldState.setTextAndPlaceCursorAtEnd(suggestion)
                                        actions.setSearchQuery(suggestion)
                                        if (actions.submitSearch()) collapseSearch()
                                    }
                                    .padding(12.dp)
                            )
                        }
                    }
                }
            }
        },
    ) { paddingValues ->
        if (state.recommendationsLoading) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.recommendations.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.empty_recommendations),
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            )
        } else if (isExpandedWidth()) {
            FeedGrid(
                rows = state.recommendations,
                key = { it.videoID },
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            ) { row ->
                FeedVideoRow(
                    row = row,
                    openVideo = actions::openVideo,
                    titleSharedKey = "youpipe-video-title-${row.videoID}",
                    overflowActions = listOf(
                        stringResource(R.string.action_not_interested) to { actions.notInterested(row.channelKey) },
                        stringResource(R.string.action_more_like_this) to { actions.moreLikeThis(row.channelKey) },
                        stringResource(R.string.action_pin_channel) to { actions.pinChannel(row.channelKey) },
                        stringResource(R.string.action_block_channel) to { actions.blockChannel(row.channelKey) },
                    ),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                items(state.recommendations, key = { it.videoID }) { row ->
                    VideoRow(
                        row = row,
                        modifier = Modifier.invisibleClickable { actions.openVideo(row.videoID) },
                        // Only the feed is keyed, not the search overlay above it: both are composed
                        // at once, and a video in each would give one key two origins.
                        titleSharedKey = "youpipe-video-title-${row.videoID}",
                        overflowActions = listOf(
                            stringResource(R.string.action_not_interested) to { actions.notInterested(row.channelKey) },
                            stringResource(R.string.action_more_like_this) to { actions.moreLikeThis(row.channelKey) },
                            stringResource(R.string.action_pin_channel) to { actions.pinChannel(row.channelKey) },
                            stringResource(R.string.action_block_channel) to { actions.blockChannel(row.channelKey) },
                        ),
                    )
                }
            }
        }
    }
}

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
