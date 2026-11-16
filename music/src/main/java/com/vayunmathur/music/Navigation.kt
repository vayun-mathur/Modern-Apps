package com.vayunmathur.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.SyncWorker
import com.vayunmathur.music.ui.AlbumDetailScreen
import com.vayunmathur.music.ui.ArtistDetailScreen
import com.vayunmathur.music.ui.MusicTabsScreen
import com.vayunmathur.music.ui.PlaylistDetailScreen
import com.vayunmathur.music.ui.SongScreen
import com.vayunmathur.music.ui.dialogs.AddToPlaylistDialog

@Composable
fun Navigation(musicViewModel: MusicViewModel) {
    val context = LocalContext.current

    // Single kickoff for all four tabs, hoisted out of MusicTabsScreen now
    // that each tab is its own entry (doing it per-tab would fire it on
    // every tab switch).
    //
    // The refresh runs here rather than being left to WorkManager because this is the first point
    // at which the audio permission is definitely granted - PermissionsChecker gates the whole
    // navigation graph - and because going through the scheduler would put the list behind a job
    // dispatch for no reason. `enqueue` only registers the content-URI triggers that notice later
    // changes to the library.
    LaunchedEffect(Unit) {
        musicViewModel.refreshLibrary()
        SyncWorker.enqueue(context)
    }

    // Start destination is Route.Home (the Songs tab): deep links and
    // process restore land there unless the retained stack says otherwise.
    val backStack = rememberNavBackStack<Route>(Route.Home)
    MainNavigation(backStack) {
        entry<Route.Home>(metadata = ListPage() + SiblingPage()) {
            MusicTabsScreen(backStack, musicViewModel, Route.Home)
        }
        entry<Route.Albums>(metadata = ListPage() + SiblingPage()) {
            MusicTabsScreen(backStack, musicViewModel, Route.Albums)
        }
        entry<Route.Artists>(metadata = ListPage() + SiblingPage()) {
            MusicTabsScreen(backStack, musicViewModel, Route.Artists)
        }
        entry<Route.Playlists>(metadata = ListPage() + SiblingPage()) {
            MusicTabsScreen(backStack, musicViewModel, Route.Playlists)
        }
        entry<Route.Song>(metadata = MorphPage()) {
            SongScreen(backStack, musicViewModel)
        }
        entry<Route.AlbumDetail>(metadata = ListDetailPage() + MorphPage()) {
            AlbumDetailScreen(backStack, musicViewModel, it.albumId)
        }
        entry<Route.ArtistDetail>(metadata = ListDetailPage() + MorphPage()) {
            ArtistDetailScreen(backStack, musicViewModel, it.artistId)
        }
        entry<Route.PlaylistDetail>(metadata = ListDetailPage() + MorphPage()) {
            PlaylistDetailScreen(backStack, musicViewModel, it.playlistId)
        }
        entry<Route.AddToPlaylistDialog>(metadata = DialogPage()) {
            AddToPlaylistDialog(backStack, musicViewModel, it.musicId)
        }
    }
}
