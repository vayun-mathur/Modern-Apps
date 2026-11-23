package com.vayunmathur.youpipe.ui

// SCAFFOLD SEARCH EXCEPTION: expandable Material3 SearchBar whose expanded overlay is the search-results/suggestions surface (see the raw-scaffold exception below)
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
            // Raw Scaffold (SearchBar) has no refresh slot: progress text affordance.
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text("Loading\u2026")
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
