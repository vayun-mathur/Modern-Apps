package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.util.YouPipeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsPage(
    backStack: NavBackStack<Route>,
    viewModel: DatabaseViewModel,
    ypvm: YouPipeViewModel,
) {
    val subscriptions by viewModel.data<Subscription>().collectAsState()
    val subscriptionCategoryPairs by viewModel.data<SubscriptionCategory>().collectAsState()
    val categories = subscriptionCategoryPairs.map { it.category }.distinct()
    val fetchProgress by ypvm.fetchProgress.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(topBar = {
        TopAppBar({ Text(stringResource(R.string.title_subscriptions)) })
    }, bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.SubscriptionsPage) }) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            if (fetchProgress in 0f..1f) {
                item {
                    ListItem(
                        headlineContent = { Text("Loading recent subscriptions") },
                        trailingContent = {
                            CircularProgressIndicator(
                                progress = { fetchProgress },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    )
                }
            }
            item {
                ListItem({
                    Text(stringResource(R.string.label_groups))
                }, trailingContent = {
                    IconButton({
                        backStack.add(Route.CreateSubscriptionCategory(null))
                    }) {
                        IconAdd()
                    }
                })
            }
            item {
                ListItem({
                    Text(stringResource(R.string.label_all_subscriptions))
                }, Modifier.clickable {
                    backStack.add(Route.SubscriptionVideosPage(null))
                })
            }
            items(categories, key = { it }) {
                ListItem({
                    Text(it)
                }, Modifier.clickable {
                    backStack.add(Route.SubscriptionVideosPage(it))
                }, trailingContent = {
                    IconButton({
                        backStack.add(Route.CreateSubscriptionCategory(it))
                    }) {
                        IconEdit()
                    }
                })
            }
            item {
                ListItem({ Text(stringResource(R.string.label_channels)) })
            }
            items(subscriptions, key = { it.id }) {
                ListItem({
                    Text(HtmlCompat.fromHtml(it.name, HtmlCompat.FROM_HTML_MODE_LEGACY).toString())
                }, Modifier.clickable {
                    backStack.add(Route.ChannelPage(it.channelID))
                }, {}, {}, {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(it.avatarURL)
                            .memoryCacheKey("sub-avatar-${it.id}")
                            .build(),
                        contentDescription = null,
                        Modifier.size(24.dp).clip(CircleShape)
                    )
                })
            }
        }
    }
}
