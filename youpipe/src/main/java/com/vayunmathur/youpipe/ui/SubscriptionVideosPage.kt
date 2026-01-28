package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.youpipe.BottomBar
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import kotlin.time.toKotlinInstant

@Composable
fun SubscriptionVideosPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val subscriptions by viewModel.data<Subscription>().collectAsState()
    var videos by remember { mutableStateOf<List<VideoInfo>>(listOf()) }

    LaunchedEffect(subscriptions) {
        val youtubeService = ServiceList.YouTube
        withContext(Dispatchers.IO) {
            videos = subscriptions.map {
                val feedExtractor = youtubeService.getFeedExtractor(it.url)
                feedExtractor.fetchPage()
                feedExtractor.initialPage.items.map {
                    VideoInfo(
                        it.name,
                        it.url,
                        it.viewCount,
                        it.uploadDate!!.instant.toKotlinInstant(),
                        it.thumbnails.first().url,
                        it.uploaderName
                    )
                }
            }.flatten().sortedBy{it.uploadDate}
        }
    }
    Scaffold(bottomBar = { BottomBar(backStack, Route.SubscriptionsPage) }) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            items(videos) {
                VideoItem(backStack, it, true)
            }
        }
    }
}