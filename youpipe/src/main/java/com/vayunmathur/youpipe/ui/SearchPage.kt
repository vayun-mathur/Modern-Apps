package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.NavBackStack
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.util.YouPipeViewModel

@Composable
fun SearchPage(
    backStack: NavBackStack<Route>,
    viewModel: DatabaseViewModel,
    ypvm: YouPipeViewModel,
) {
    val searchQuery by ypvm.searchQuery.collectAsState()
    val suggestions by ypvm.suggestions.collectAsState()
    val searchResults by ypvm.searchResults.collectAsState()

    fun submit() {
        val watchID = ypvm.resolveWatchUrl()
        if (watchID != null) {
            backStack.add(Route.VideoPage(watchID))
        } else {
            ypvm.performSearch()
        }
    }

    Scaffold(bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.SearchPage) }) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            TextField(
                value = searchQuery,
                onValueChange = { ypvm.setSearchQuery(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.label_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                singleLine = true
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (searchResults.isNotEmpty()) {
                    items(searchResults, key = {
                        when (it) {
                            is VideoInfo -> "v-${it.videoID}"
                            is ChannelInfo -> "c-${it.channelID}"
                            else -> it.hashCode().toString()
                        }
                    }) { item ->
                        if (item is VideoInfo)
                            VideoItem(backStack, viewModel, item, true)
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
                                    ypvm.setSearchQuery(suggestion)
                                    submit()
                                }
                                .padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelItem(backStack: NavBackStack<Route>, channelInfo: ChannelInfo) {
    val context = LocalContext.current
    ListItem({
        Text(channelInfo.name, style = MaterialTheme.typography.titleMedium)
    }, Modifier.clickable {
        backStack.add(Route.ChannelPage(channelInfo.channelID))
    }, {

    }, {
        Text(stringResource(R.string.subscribers_count, countString(context, channelInfo.subscribers)))
    }, {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(channelInfo.avatar)
                .memoryCacheKey("channel-avatar-${channelInfo.channelID}")
                .build(),
            contentDescription = null,
            Modifier.size(50.dp).clip(CircleShape)
        )
    })
}
