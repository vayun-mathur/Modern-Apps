package com.vayunmathur.youpipe.ui.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInfoCard
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.tv.tvGridHeader
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/**
 * TV saved library: Downloads + Watch later as full-information cards, then user
 * playlists as a 3-column grid of [TvInfoCard]s with counts. Creating a playlist is the
 * header action; deleting/renaming happens on the playlist detail screen.
 *
 * The phone reorders playlists with a drag handle; a D-pad has no drag, and stacked
 * per-row move buttons break the one-focus-axis rule — so reorder stays phone-only.
 */
@Composable
fun TvSavedPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val playlists by ypvm.playlists.collectAsStateWithLifecycle()
    val downloadedVideos by ypvm.downloadedVideos.collectAsStateWithLifecycle()
    val allPlaylistItems by ypvm.allPlaylistItems.collectAsStateWithLifecycle()
    val counts = remember(allPlaylistItems) { allPlaylistItems.groupingBy { it.playlistId }.eachCount() }
    val watchLater = playlists.firstOrNull { it.mandatory }
    val userPlaylists = playlists.filter { !it.mandatory }
    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus)
    Column(Modifier.fillMaxSize()) {
        TvSavedHeader(backStack)
        TvSavedGrid(
            backStack = backStack,
            watchLater = watchLater,
            userPlaylists = userPlaylists,
            counts = counts,
            downloadedCount = downloadedVideos.size,
            modifier = Modifier.fillMaxSize().weight(1f),
        )
    }
}

@Composable
private fun TvSavedHeader(backStack: NavBackStack<TvRoute>) {
    TvScreenHeader(
        title = stringResource(UiR.string.saved),
        actions = {
            TvButton(
                text = stringResource(R.string.new_playlist),
                onClick = { backStack.add(TvRoute.CreatePlaylist) },
                icon = { IconAdd() },
            )
        },
    )
}

@Composable
private fun TvSavedGrid(
    backStack: NavBackStack<TvRoute>,
    watchLater: Playlist?,
    userPlaylists: List<Playlist>,
    counts: Map<Long, Int>,
    downloadedCount: Int,
    modifier: Modifier = Modifier,
) {
    val savedTitle = stringResource(UiR.string.saved)
    TvGrid(columns = 3, modifier = modifier) {
        tvSavedPinnedSection(backStack, watchLater, counts, downloadedCount)
        if (userPlaylists.isEmpty()) {
            tvFullWidth(key = "empty") {
                TvEmptyState(title = stringResource(R.string.playlist_empty))
            }
        } else {
            tvGridHeader(key = "playlists-header", title = savedTitle)
            tvSavedPlaylistItems(backStack, userPlaylists, counts)
        }
    }
}

private fun LazyGridScope.tvSavedPinnedSection(
    backStack: NavBackStack<TvRoute>,
    watchLater: Playlist?,
    counts: Map<Long, Int>,
    downloadedCount: Int,
) {
    tvFullWidth(key = "pinned") {
        Column {
            TvInfoCard(
                title = stringResource(R.string.title_downloads),
                onClick = { backStack.add(TvRoute.Downloads) },
                subtitle = pluralStringResource(R.plurals.playlist_video_count, downloadedCount, downloadedCount),
                leading = { IconDownload() },
            )
            if (watchLater != null) {
                TvInfoCard(
                    title = stringResource(R.string.playlist_watch_later),
                    onClick = { backStack.add(TvRoute.PlaylistDetail(watchLater.id)) },
                    subtitle = pluralStringResource(
                        R.plurals.playlist_video_count,
                        counts[watchLater.id] ?: 0,
                        counts[watchLater.id] ?: 0,
                    ),
                    leading = { IconList() },
                )
            }
        }
    }
}

private fun LazyGridScope.tvSavedPlaylistItems(
    backStack: NavBackStack<TvRoute>,
    userPlaylists: List<Playlist>,
    counts: Map<Long, Int>,
) {
    items(userPlaylists.size, key = { userPlaylists[it].id }) { index ->
        val playlist = userPlaylists[index]
        TvInfoCard(
            title = playlist.name,
            onClick = { backStack.add(TvRoute.PlaylistDetail(playlist.id)) },
            subtitle = pluralStringResource(R.plurals.playlist_video_count, counts[playlist.id] ?: 0, counts[playlist.id] ?: 0),
            leading = { IconList() },
        )
    }
}
