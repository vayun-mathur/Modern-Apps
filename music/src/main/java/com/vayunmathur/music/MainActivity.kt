package com.vayunmathur.music

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.MediaController
import androidx.room.migration.Migration
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.MusicDatabase
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.R
import com.vayunmathur.music.ui.AlbumDetailScreen
import com.vayunmathur.music.ui.AlbumScreen
import com.vayunmathur.music.ui.ArtistDetailScreen
import com.vayunmathur.music.ui.ArtistScreen
import com.vayunmathur.music.ui.HomeScreen
import com.vayunmathur.music.ui.PlaylistDetailScreen
import com.vayunmathur.music.ui.PlaylistScreen
import com.vayunmathur.music.ui.SongScreen
import com.vayunmathur.music.ui.dialogs.AddToPlaylistDialog
import kotlinx.serialization.Serializable
import com.vayunmathur.music.util.PlaybackManager
import com.vayunmathur.music.util.SyncWorker

class MainActivity : ComponentActivity() {
    var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<MusicDatabase>(listOf(MIGRATION_1_2))
        val viewModel = DatabaseViewModel(db,Music::class to db.musicDao(), Album::class to db.albumDao(), Artist::class to db.artistDao(), Playlist::class to db.playlistDao(), matchingDao = db.matchingDao())
        val pm = PlaybackManager.getInstance(this)
        setContent {
            DynamicTheme {
                if(Build.VERSION.SDK_INT >= 33) {
                    PermissionsChecker(
                        arrayOf(Manifest.permission.READ_MEDIA_AUDIO),
                        getString(R.string.grant_audio_permissions)
                    ) {
                        Navigation(viewModel)
                    }
                } else {
                    PermissionsChecker(
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                        getString(R.string.grant_storage_permissions)
                    ) {
                        Navigation(viewModel)
                    }
                }
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
    data object Playlists: Route
    @Serializable
    data object Song: Route

    @Serializable
    data class AlbumDetail(val albumId: Long): Route

    @Serializable
    data class ArtistDetail(val artistId: Long): Route

    @Serializable
    data class PlaylistDetail(val playlistId: Long): Route

    @Serializable
    data class AddToPlaylistDialog(val musicId: Long): Route
}

@Composable
fun Navigation(viewModel: DatabaseViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        SyncWorker.runOnce(context)
        SyncWorker.enqueue(context)
    }
    val backStack = rememberNavBackStack<Route>(Route.Home)
    MainNavigation(backStack) {
        entry<Route.Home> {
            HomeScreen(backStack, viewModel)
        }
        entry<Route.Song> {
            SongScreen(backStack)
        }
        entry<Route.Albums> {
            AlbumScreen(backStack, viewModel)
        }
        entry<Route.Artists> {
            ArtistScreen(backStack, viewModel)
        }
        entry<Route.Playlists> {
            PlaylistScreen(backStack, viewModel)
        }
        entry<Route.AlbumDetail> {
            AlbumDetailScreen(backStack, viewModel, it.albumId)
        }
        entry<Route.ArtistDetail> {
            ArtistDetailScreen(backStack, viewModel, it.artistId)
        }
        entry<Route.PlaylistDetail> {
            PlaylistDetailScreen(backStack, viewModel, it.playlistId)
        }
        entry<Route.AddToPlaylistDialog>(metadata = DialogPage()) {
            AddToPlaylistDialog(backStack, viewModel, it.musicId)
        }
    }
}

val MIGRATION_1_2 = Migration(1, 2) {
    it.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `Playlist` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
            `name` TEXT NOT NULL
        )
        """.trimIndent()
    )
}
