package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.SubscriptionVideo

@Composable
fun SubscriptionVideosPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val videos by viewModel.data<SubscriptionVideo>().collectAsState()

    Scaffold(bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.SubscriptionsPage) }) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            items(videos.map {
                VideoInfo(it.name, it.url, it.views, it.uploadDate, it.thumbnailURL, it.author)
            }) {
                VideoItem(backStack, it, true)
            }
        }
    }
}