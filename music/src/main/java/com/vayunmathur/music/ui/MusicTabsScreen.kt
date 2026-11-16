package com.vayunmathur.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconAlbum
import com.vayunmathur.library.ui.IconLibraryMusic
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.isNavLeaving
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.ui.components.PlayingBottomBar

/**
 * Hosts one of the four main tabs (Songs / Albums / Artists / Playlists) with
 * the now-playing controls and the tab bar / rail pinned across all four.
 *
 * Tabs are sibling [Route]s, not pager pages: each tab is registered as its
 * own entry in `Navigation` with `SiblingPage()` metadata, and the
 * route-backed [TabbedPagerScaffold] switches between them with
 * `backStack.reset(tab.route)` on tab select — so back, deep links (the
 * start destination is [Route.Home]) and process restore see the real
 * screen, and a detail pushed over a tab (tap an album → AlbumDetail →
 * back) returns to that tab. [currentTab] is the route this instance was
 * registered for; it, not the (possibly detail) top of the stack, decides
 * which tab's content renders, so a list pane beside a detail still shows
 * its tab. Per-tab `rememberSaveable` state (search, scroll) lives in the
 * tab contents themselves and survives via their entry's saved state.
 *
 * The library refresh kickoff lives in `Navigation`, not here: there is
 * one instance of this host per tab entry, and doing it here would fire
 * it on every tab switch.
 */
@Composable
fun MusicTabsScreen(
    backStack: NavBackStack<Route>,
    musicViewModel: MusicViewModel,
    currentTab: Route = Route.Home,
) {
    // Which element the song morph should use as its counterpart.
    //
    // The graph loops here: a row tap carries the song up to the player, but coming back the song
    // belongs in the mini-player, not in the row it left. Both are composed the whole time, so a
    // static key would leave the morph with two origins and no way to choose.
    //
    // Leaving, it is whichever of the two was actually tapped. Arriving - the way back from the
    // player - it is always the bar.
    var tappedSongRow by remember { mutableStateOf(false) }
    val leaving = isNavLeaving()
    val barOwnsSongKeys = !leaving || !tappedSongRow

    TabbedPagerScaffold(
        backStack = backStack,
        tabs = listOf(
            RouteTab(Route.Home, stringResource(UiR.string.home)) { IconLibraryMusic() },
            RouteTab(Route.Albums, stringResource(R.string.label_albums)) { IconAlbum() },
            RouteTab(Route.Artists, stringResource(R.string.nav_artists)) { IconPerson() },
            RouteTab(Route.Playlists, stringResource(R.string.nav_playlists)) { IconLibraryMusic() },
        ),
        leadingBottomBar = {
            PlayingBottomBar(
                musicViewModel,
                backStack,
                owsSharedKeys = barOwnsSongKeys,
                onOpen = { tappedSongRow = false; backStack.add(Route.Song) },
            )
        },
    ) {
        // Ignore the scaffold's top-of-stack route: on a detail destination
        // this host is the list pane underneath, and must keep showing its tab.
        when (currentTab) {
            Route.Albums -> AlbumsTabContent(backStack, musicViewModel)
            Route.Artists -> ArtistsTabContent(backStack, musicViewModel)
            Route.Playlists -> PlaylistsTabContent(backStack, musicViewModel)
            else -> HomeTabContent(
                backStack,
                musicViewModel,
                rowOwnsSongKeys = leaving && tappedSongRow,
                onSongTapped = { tappedSongRow = true },
            )
        }
    }
}
