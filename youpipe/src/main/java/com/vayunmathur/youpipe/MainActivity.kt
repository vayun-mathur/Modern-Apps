package com.vayunmathur.youpipe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.youpipe.ui.MainPage
import com.vayunmathur.youpipe.ui.VideoPage
import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.NewPipe


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NewPipe.init(MyDownloader())
        setContent {
            DynamicTheme {
                Navigation()
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object MainPage : Route

    @Serializable
    data class VideoPage(val uri: String) : Route
}

@Composable
fun Navigation() {
    val backStack = rememberNavBackStack<Route>(Route.VideoPage("https://www.youtube.com/watch?v=Guoak9N5lAo"))
    MainNavigation(backStack) {
        entry<Route.MainPage> {
            MainPage(backStack)
        }
        entry<Route.VideoPage> {
            VideoPage(backStack, it.uri)
        }
    }
}