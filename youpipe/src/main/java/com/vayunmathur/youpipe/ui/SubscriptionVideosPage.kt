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
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.data.SubscriptionVideo

@Composable
fun SubscriptionVideosPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, category: String?) {
    val videos by viewModel.data<SubscriptionVideo>().collectAsState()
    val subscriptions by viewModel.data<Subscription>().collectAsState()
    val pairs by viewModel.data<SubscriptionCategory>().collectAsState()

    val subsInCategory = pairs.filter { it.category == category }.map { pair ->
        subscriptions.first { it.id == pair.subscriptionID }
    }

    val videosInSubs = if(category == null) videos else subsInCategory.flatMap { sub ->
        videos.filter { it.channelID == sub.id }
    }

    Scaffold(bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.SubscriptionsPage) }) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            items(videosInSubs.map {
                VideoInfo(it.name, it.id, it.duration, it.views, it.uploadDate, it.thumbnailURL, it.author)
            }.sortedByDescending { it.uploadDate }) {
                VideoItem(backStack, viewModel, it, true)
            }
        }
    }
}