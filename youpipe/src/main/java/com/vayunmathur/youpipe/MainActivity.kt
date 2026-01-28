package com.vayunmathur.youpipe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.ui.ChannelPage
import com.vayunmathur.youpipe.ui.SearchPage
import com.vayunmathur.youpipe.ui.SubscriptionVideosPage
import com.vayunmathur.youpipe.ui.SubscriptionsPage
import com.vayunmathur.youpipe.ui.VideoPage
import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.NewPipe


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<SubscriptionDatabase>()
        val viewModel = DatabaseViewModel(Subscription::class to db.subscriptionDao())
        NewPipe.init(MyDownloader())
        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object SearchPage : Route

    @Serializable
    data class VideoPage(val uri: String) : Route

    @Serializable
    data class ChannelPage(val uri: String): Route

    @Serializable
    data object SubscriptionsPage: Route

    @Serializable
    data object SubscriptionVideosPage: Route
}

@Composable
fun Navigation(viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.SearchPage)//Route.ChannelPage("https://www.youtube.com/channel/UC9RM-iSvTu1uPJb8X5yp3EQ"))
    MainNavigation(backStack) {
        entry<Route.SearchPage> {
            SearchPage(backStack)
        }
        entry<Route.VideoPage> {
            VideoPage(backStack, it.uri)
        }
        entry<Route.ChannelPage> {
            ChannelPage(backStack, viewModel, it.uri)
        }
        entry<Route.SubscriptionsPage> {
            SubscriptionsPage(backStack, viewModel)
        }
        entry<Route.SubscriptionVideosPage> {
            SubscriptionVideosPage(backStack, viewModel)
        }
    }
}

data class BottomBarItem(val name: String, val route: Route, val icon: Int)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BottomBar(backStack: NavBackStack<Route>, currentPage: Route) {
    val pages = listOf(
        BottomBarItem("Search", Route.SearchPage, R.drawable.outline_search_24),
        BottomBarItem("Subscriptions", Route.SubscriptionsPage, R.drawable.outline_subscriptions_24)
    )
    FlexibleBottomAppBar {
        pages.forEach { item ->
            NavigationBarItem(
                selected = currentPage == item.route,
                onClick = {
                    if (backStack.last() != item.route) {
                        backStack.add(item.route)
                    }
                },
                label = { Text(item.name) },
                icon = { Icon(painterResource(item.icon), null) }
            )
        }
    }
}