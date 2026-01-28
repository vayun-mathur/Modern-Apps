package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import coil.compose.AsyncImage
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.youpipe.BottomBar
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.Subscription

@Composable
fun SubscriptionsPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val subscriptions by viewModel.data<Subscription>().collectAsState()
    Scaffold(bottomBar = { BottomBar(backStack, Route.SubscriptionsPage) }) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            item {
                Text("Groups:", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleMedium)
            }
            item {
                ListItem({
                    Text("All Subscriptions")
                }, Modifier.clickable{
                    backStack.add(Route.SubscriptionVideosPage)
                })
            }
            item {
                Text("Channels:", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleMedium)
            }
            items(subscriptions) {
                ListItem({
                    Text(it.name)
                }, Modifier.clickable{
                    backStack.add(Route.ChannelPage(it.url))
                }, {}, {}, {
                    AsyncImage(
                        model = it.avatarURL,
                        contentDescription = null,
                        Modifier.size(24.dp).clip(CircleShape)
                    )
                })
            }
        }
    }
}