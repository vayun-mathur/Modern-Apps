package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedContent
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.network.decodeHtml

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsPage(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
) {
    val subscriptions by youPipeViewModel.subscriptions.collectAsState()
    val categories by youPipeViewModel.categoryNames.collectAsState()
    val fetchProgress by youPipeViewModel.fetchProgress.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // A plain scrolling list with no top bar: LazyListScaffold with no title draws none,
    // and owns the list insets the raw Scaffold + LazyColumn pair was deriving by hand.
    LazyListScaffold(
    ) {
            if (fetchProgress in 0f..1f) {
                item {
                    ListItem(
                        content = { Text(stringResource(R.string.loading_recent_subscriptions)) },
                        trailingContent = {
                            LinearProgressIndicator(
                                progress = { fetchProgress },
                                modifier = Modifier.width(64.dp)
                            )
                        }
                    )
                }
            }
            item {
                ListItem(trailingContent = {
                    IconButton({
                        backStack.add(Route.CreateSubscriptionCategory(null))
                    }) {
                        IconAdd()
                    }
                }) {
                    Text(stringResource(R.string.label_groups))
                }
            }
            item {
                ListItem(modifier = Modifier.clickable {
                    backStack.add(Route.SubscriptionVideosPage(null))
                }) {
                    Text(stringResource(R.string.label_all_subscriptions))
                }
            }
            items(categories, key = { it }) {
                ListItem(modifier = Modifier.clickable {
                    backStack.add(Route.SubscriptionVideosPage(it))
                }, trailingContent = {
                    IconButton({
                        backStack.add(Route.CreateSubscriptionCategory(it))
                    }) {
                        IconEdit()
                    }
                }) {
                    Text(it)
                }
            }
            item {
                ListItem { Text(stringResource(R.string.label_channels)) }
            }
            items(subscriptions, key = { it.id }) {
                // Only this list is keyed, not the channel rows in the search overlay on the next
                // tab: one key needs one origin, and the pager composes both mid-swipe.
                ListItem(modifier = Modifier
                    .sharedContainer("youpipe-channel-${it.channelID}")
                    .clickable { backStack.add(Route.ChannelPage(it.channelID)) },
                    overlineContent = {}, supportingContent = {}, leadingContent = {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(it.avatarURL)
                            .memoryCacheKey("sub-avatar-${it.id}")
                            .build(),
                        contentDescription = null,
                        Modifier.sharedContent("youpipe-channel-avatar-${it.channelID}")
                            .size(24.dp)
                            .clip(CircleShape)
                    )
                }) {
                    Text(it.name.decodeHtml())
                }
            }
    }
}
