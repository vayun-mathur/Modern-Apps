package com.vayunmathur.youpipe

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionVideo
import com.vayunmathur.youpipe.ui.ChannelPage
import com.vayunmathur.youpipe.ui.HistoryPage
import com.vayunmathur.youpipe.ui.PlaybackService
import com.vayunmathur.youpipe.ui.SearchPage
import com.vayunmathur.youpipe.ui.SubscriptionVideosPage
import com.vayunmathur.youpipe.ui.SubscriptionsPage
import com.vayunmathur.youpipe.ui.VideoPage
import com.vayunmathur.youpipe.ui.dialog.CreateSubscriptionCategory
import com.vayunmathur.youpipe.ui.setupHourlyTask
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.NewPipe

internal fun Context.findActivity(): ComponentActivity {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    throw IllegalStateException("Picture in picture should be called in the context of an Activity")
}

@Composable
fun rememberIsInPipMode(): Boolean {
    val activity = LocalContext.current.findActivity()
    var pipMode by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val observer = Consumer<PictureInPictureModeChangedInfo> { info ->
            pipMode = info.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(
            observer
        )
        onDispose { activity.removeOnPictureInPictureModeChangedListener(observer) }
    }
    return pipMode
}



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<SubscriptionDatabase>()
        val viewModel = DatabaseViewModel(db,
            Subscription::class to db.subscriptionDao(),
            SubscriptionVideo::class to db.subscriptionVideoDao(),
            HistoryVideo::class to db.historyVideoDao(),
            SubscriptionCategory::class to db.subscriptionCategoryDao()
        )
        NewPipe.init(MyDownloader())
        setContent {
            DynamicTheme {
                LaunchedEffect(Unit) {
                    delay(2000)
                    setupHourlyTask(this@MainActivity)
                }
                Navigation(getRoute(intent.data), viewModel)
            }
        }
    }
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        // If we were in PiP and we are no longer in it, and the activity is
        // finishing, it means the user closed the PiP window.
        if (!isInPictureInPictureMode && isFinishing) {
            val intent = Intent(this, PlaybackService::class.java)
            stopService(intent) // This forces the service to die immediately
        }
    }

    override fun onDestroy() {
        // If the activity is destroyed (closing PiP window), stop the service.
        // This is the most reliable "kill switch" for the audio.
        val intent = Intent(this, PlaybackService::class.java)
        stopService(intent)
        super.onDestroy()
    }
}

fun getRoute(uri: Uri?): Route {
    if(uri != null) {
        if("watch" in uri.pathSegments && "v" in uri.queryParameterNames) {
            return Route.VideoPage(videoURLtoID(uri.toString()))
        }
    }
    return Route.SubscriptionsPage
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object SearchPage : Route

    @Serializable
    data class VideoPage(val videoID: Long) : Route

    @Serializable
    data class ChannelPage(val channelID: String): Route

    @Serializable
    data object SubscriptionsPage: Route

    @Serializable
    data class SubscriptionVideosPage(val category: String?): Route

    @Serializable
    data class CreateSubscriptionCategory(val id: String?): Route

    @Serializable
    data object History: Route
}

@Composable
fun Navigation(initialRoute: Route, viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack(initialRoute)
    MainNavigation(backStack) {
        entry<Route.SearchPage> {
            SearchPage(backStack, viewModel)
        }
        entry<Route.VideoPage> {
            VideoPage(backStack, viewModel, it.videoID)
        }
        entry<Route.ChannelPage> {
            ChannelPage(backStack, viewModel, it.channelID)
        }
        entry<Route.SubscriptionsPage> {
            SubscriptionsPage(backStack, viewModel)
        }
        entry<Route.SubscriptionVideosPage> {
            SubscriptionVideosPage(backStack, viewModel, it.category)
        }
        entry<Route.CreateSubscriptionCategory>(metadata = DialogPage()) {
            CreateSubscriptionCategory(backStack, viewModel, it.id)
        }
        entry<Route.History> {
            HistoryPage(backStack, viewModel)
        }
    }
}

val MAIN_BOTTOM_BAR_ITEMS = listOf(
    BottomBarItem("Search", Route.SearchPage, R.drawable.outline_search_24),
    BottomBarItem("Subscriptions", Route.SubscriptionsPage, R.drawable.outline_subscriptions_24),
    BottomBarItem("History", Route.History, R.drawable.baseline_history_24)
)