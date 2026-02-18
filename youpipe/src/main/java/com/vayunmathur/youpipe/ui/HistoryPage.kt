package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.HistoryVideo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val historyVideos by viewModel.data<HistoryVideo>().collectAsState()
    val history = historyVideos.sortedByDescending { it.timestamp }

    Scaffold(topBar = {
        TopAppBar({Text("History")})
    }, bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.History) }) {paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            items(history) {historyItem ->
                VideoItem(backStack, viewModel, historyItem.videoItem, true)
            }
        }
    }
}