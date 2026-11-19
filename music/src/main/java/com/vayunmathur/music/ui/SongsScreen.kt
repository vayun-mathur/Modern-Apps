package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.ui.components.ShufflePlayFab
import com.vayunmathur.music.platform.AlbumArt
import com.vayunmathur.music.ui.components.AddToPlaylistButton
import com.vayunmathur.music.platform.MusicActions
import com.vayunmathur.music.platform.SongsUiState

/** Queue id the songs tab plays under; [SOURCE_ALL_SONGS_NAME] is its display label. */
internal const val SOURCE_ALL_SONGS = "all_songs"
internal const val SOURCE_ALL_SONGS_NAME = "All Songs"

/**
 * Songs tab content. No Scaffold / no BottomNavBar — those live in the
 * surrounding [MusicTabsScreen]. ListPage's own Scaffold is kept (it owns
 * the TopAppBar with the embedded search bar and the shuffle FAB).
 */
@Composable
fun SongsScreen(
    state: SongsUiState,
    actions: MusicActions,
    backStack: NavBackStack<Route>,
    onSongTapped: () -> Unit = {},
) {
    // Only the row for the song that is about to play can be the morph origin, and only when this
    // list - rather than the mini-player - is the one the player was opened from.
    @Composable
    fun songKey(song: Music, slot: String): Modifier =
        if (!state.rowOwnsSongKeys) Modifier
        else Modifier.sharedText("music-song-$slot-${song.id}")

    ListPage<Music, Route, Route.Song>(backStack, state.songs, stringResource(R.string.app_name), { song ->
        val isPlaying = song.id == state.playingSongId
        Text(
            text = song.title,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Unspecified,
            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
            modifier = songKey(song, "title"),
        )
    }, { song ->
        Text(song.artist, modifier = songKey(song, "artist"))
    }, { toPlay ->
        val allSongs = state.songs
        val toPlayIndex = allSongs.indexOfFirst { it.id == toPlay }
        actions.playSong(allSongs, toPlayIndex, sourceId = SOURCE_ALL_SONGS, sourceName = SOURCE_ALL_SONGS_NAME)
        onSongTapped()
        Route.Song
    }, leadingContent = { song ->
        val isPlaying = song.id == state.playingSongId
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isPlaying) {
                IconPlay(modifier = Modifier.size(24.dp).padding(end = 8.dp))
            }
            AlbumArt(
                song.uri.toUri(),
                Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).then(
                    if (!state.rowOwnsSongKeys) Modifier
                    else Modifier.sharedContainer("music-song-art-${song.id}")
                ),
            )
        }
    }, trailingContent = { song ->
        AddToPlaylistButton(backStack, song)
    }, itemModifier = { Modifier.clip(RoundedCornerShape(12.dp)) },
    itemColors = { song ->
        if (song.id == state.playingSongId) {
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                trailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                supportingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            ListItemDefaults.colors()
        }
    }, searchEnabled = true, fab = {
        ShufflePlayFab(state.songs) {
            actions.playShuffled(state.songs, sourceId = SOURCE_ALL_SONGS, sourceName = SOURCE_ALL_SONGS_NAME)
        }
    },
    // Without this the default is `it.toString()`, which on a data class is the whole row -
    // "Music(id=42, title=..., artistId=..., albumId=...)". That allocated a long string per song
    // per keystroke and matched on field names and numeric ids, so typing "1" hit everything.
    searchString = { "${it.title} ${it.artist} ${it.album}" },
    // No sortOrder: getSongs already returns the library sorted by title.
    loading = state.loading)
}
