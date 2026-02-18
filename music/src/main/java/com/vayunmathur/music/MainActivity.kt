package com.vayunmathur.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.session.MediaController
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.music.database.Music
import com.vayunmathur.music.database.MusicDatabase
import com.vayunmathur.music.ui.HomeScreen
import com.vayunmathur.music.ui.SongScreen
import com.vayunmathur.music.ui.saveMediaToFile
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<MusicDatabase>()
        val viewModel = DatabaseViewModel(db,Music::class to db.musicDao())
        val pm = PlaybackManager.getInstance(this)
        setContent {
            DynamicTheme {
                LaunchedEffect(Unit) {
                    saveMediaToFile(this@MainActivity, viewModel)
                }
                Navigation(viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Home: Route
    @Serializable
    data class Song(val songID: Long): Route
}

@Composable
fun Navigation(viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    MainNavigation(backStack) {
        entry<Route.Home> {
            HomeScreen(backStack, viewModel)
        }
        entry<Route.Song> {
            SongScreen(backStack, viewModel, it.songID)
        }
    }
}