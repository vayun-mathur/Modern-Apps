package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.util.YouPipeViewModel

@OptIn(ExperimentalMaterial3Api::class)
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

    var expanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        youPipeViewModel.loadRecommendations()
    }

    fun submit() {
        val watchID = youPipeViewModel.resolveWatchUrl()
        if (watchID != null) {
            expanded = false
            backStack.add(Route.VideoPage(watchID))
        } else {
            youPipeViewModel.performSearch()
        }
    }

    Scaffold(
        topBar = {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = searchQuery,
                        onQueryChange = { youPipeViewModel.setSearchQuery(it) },
                        onSearch = { submit() },
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        placeholder = { Text(stringResource(R.string.label_search)) },
                    )
                },
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                LazyColumn {
                    if (searchResults.isNotEmpty()) {
                        items(searchResults, key = {
                            when (it) {
                                is VideoInfo -> "v-${it.videoID}"
                                is ChannelInfo -> "c-${it.channelID}"
                                else -> it.hashCode().toString()
                            }
                        }) { item ->
                            if (item is VideoInfo)
                                VideoItem(backStack, youPipeViewModel, item, true, onClick = { expanded = false; backStack.add(Route.VideoPage(item.videoID)) })
                            else if (item is ChannelInfo)
                                ChannelItem(backStack, item)
                        }
                    } else {
                        items(suggestions, key = { it }) { suggestion ->
                            Text(
                                text = suggestion,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        youPipeViewModel.setSearchQuery(suggestion)
                                        submit()
                                    }
                                    .padding(12.dp)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.SearchPage) }
    ) { paddingValues ->
        if (recommendationsLoading) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (recommendations.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.empty_recommendations),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                items(recommendations, key = { it.video.videoID }) { ranked ->
                    val video = ranked.video
                    val channelKey = video.author.lowercase()
                    VideoItem(
                        backStack,
                        youPipeViewModel,
                        video,
                        true,
                        reason = ranked.reason,
                        overflowActions = listOf(
                            stringResource(R.string.action_not_interested) to { youPipeViewModel.removeInterest(channelKey = channelKey) },
                            stringResource(R.string.action_more_like_this) to { youPipeViewModel.boostChannel(channelKey) },
                            stringResource(R.string.action_pin_channel) to { youPipeViewModel.pinChannel(channelKey) },
                            stringResource(R.string.action_block_channel) to { youPipeViewModel.blockChannel(channelKey) },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
fun ChannelItem(backStack: NavBackStack<Route>, channelInfo: ChannelInfo) {
    val context = LocalContext.current
    ListItem(modifier = Modifier.clickable {
        backStack.add(Route.ChannelPage(channelInfo.channelID))
    }, overlineContent = {

    }, supportingContent = {
        Text(stringResource(R.string.subscribers_count, countString(context, channelInfo.subscribers)))
    }, leadingContent = {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(channelInfo.avatar)
                .memoryCacheKey("channel-avatar-${channelInfo.channelID}")
                .build(),
            contentDescription = null,
            Modifier.size(50.dp).clip(CircleShape)
        )
    }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) {
        Text(channelInfo.name, style = MaterialTheme.typography.titleMedium)
    }
}
