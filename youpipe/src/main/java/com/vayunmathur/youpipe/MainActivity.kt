package com.vayunmathur.youpipe

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.BottomBarItem
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
                Navigation(getRoute(intent.data), viewModel)
            }
        }
    }
}

fun getRoute(uri: Uri?): Route {
    if(uri != null) {
        if("watch" in uri.pathSegments) {
            return Route.VideoPage(uri.toString())
        }
    }
    return Route.SubscriptionsPage
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
fun Navigation(initialRoute: Route, viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack(initialRoute)
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

val MAIN_BOTTOM_BAR_ITEMS = listOf(
    BottomBarItem("Search", Route.SearchPage, R.drawable.outline_search_24),
    BottomBarItem("Subscriptions", Route.SubscriptionsPage, R.drawable.outline_subscriptions_24)
)