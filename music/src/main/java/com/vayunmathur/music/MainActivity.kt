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
import com.vayunmathur.music.database.Album
import com.vayunmathur.music.database.Artist
import com.vayunmathur.music.database.Music
import com.vayunmathur.music.database.MusicDatabase
import com.vayunmathur.music.ui.AlbumDetailScreen
import com.vayunmathur.music.ui.AlbumScreen
import com.vayunmathur.music.ui.ArtistDetailScreen
import com.vayunmathur.music.ui.ArtistScreen
import com.vayunmathur.music.ui.HomeScreen
import com.vayunmathur.music.ui.SongScreen
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<MusicDatabase>()
        val viewModel = DatabaseViewModel(db,Music::class to db.musicDao(), Album::class to db.albumDao(), Artist::class to db.artistDao(), matchingDao = db.matchingDao())
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
    data object Albums: Route
    @Serializable
    data object Artists: Route
    @Serializable
    data object Song: Route

    @Serializable
    data class AlbumDetail(val albumId: Long): Route

    @Serializable
    data class ArtistDetail(val artistId: Long): Route
}

@Composable
fun Navigation(viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    MainNavigation(backStack) {
        entry<Route.Home> {
            HomeScreen(backStack, viewModel)
        }
        entry<Route.Song> {
            SongScreen(backStack, viewModel)
        }
        entry<Route.Albums> {
            AlbumScreen(backStack, viewModel)
        }
        entry<Route.Artists> {
            ArtistScreen(backStack, viewModel)
        }
        entry<Route.AlbumDetail> {
            AlbumDetailScreen(backStack, viewModel, it.albumId)
        }
        entry<Route.ArtistDetail> {
            ArtistDetailScreen(backStack, viewModel, it.artistId)
        }
    }
}