package com.vayunmathur.music.ui

import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import com.vayunmathur.library.ui.IconCast
import com.vayunmathur.library.ui.IconCastConnected
import com.vayunmathur.music.platform.CastPlayback
import com.vayunmathur.sdk.cast.CastClient
import com.vayunmathur.sdk.cast.CastPickerContract
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.isNavLeaving
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.platform.Lyrics
import com.vayunmathur.music.platform.MusicActions
import com.vayunmathur.music.platform.NowPlayingUiState
import com.vayunmathur.music.platform.PlaybackSource
import com.vayunmathur.music.platform.classifyLyrics
import com.vayunmathur.music.platform.currentLyricIndex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    state: NowPlayingUiState,
    actions: MusicActions,
    backStack: NavBackStack<Route>,
    /** Expanded queue panel. SongScreen fills it; previews leave it empty. */
    queue: @Composable (Modifier) -> Unit = {},
) {
    // UI States
    var showLyrics by remember { mutableStateOf(false) }
    // The song this screen is showing, for the shared-element keys it exchanges with the songs list
    // and the mini-player.
    val songId = state.songId
    // Whether this screen is on its way out. The artist and album lines are morph origins when it
    // is, and part of the song's own group when it is not - one element cannot be both at once.
    val leaving = isNavLeaving()
    // Lyrics are read from the playing file's embedded tags (see EmbeddedLyrics); when a
    // track carries none this is Lyrics.None and the overlay says so gracefully.
    val lyrics = remember(state.lyrics) { classifyLyrics(state.lyrics) }
    val currentIndex = remember(lyrics, state.positionMs) {
        (lyrics as? Lyrics.Timed)?.let { currentLyricIndex(it.lines, state.positionMs) } ?: -1
    }

    // RAW SCAFFOLD EXCEPTION: nested now-playing scaffold
    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = { IconNavigation(backStack) },
                actions = {
                    CastButton(state = state)
                    val sourceName = state.sourceName
                    if (state.sourceId != null && sourceName != null) {
                        TextButton(onClick = {
                            // setLast, not reset: replacing the top of the stack keeps this an
                            // ordinary transition that the shared elements can animate across,
                            // where rebuilding the stack wholesale gave them no pair of endpoints.
                            // Popping instead when the source is already the entry underneath
                            // avoids stacking a second copy of a screen the user came from.
                            fun goTo(route: Route) {
                                val stack = backStack.backStack
                                if (stack.getOrNull(stack.lastIndex - 1) == route) backStack.pop()
                                else backStack.setLast(route)
                            }
                            when (val src = PlaybackSource.parse(state.sourceId)) {
                                PlaybackSource.AllSongs -> goTo(Route.Home)
                                is PlaybackSource.Album -> goTo(Route.AlbumDetail(src.albumId))
                                is PlaybackSource.Playlist -> goTo(Route.PlaylistDetail(src.playlistId))
                                is PlaybackSource.Artist -> goTo(Route.ArtistDetail(src.artistId))
                                null -> {}
                            }
                        }) {
                            Text(
                                stringResource(R.string.go_to_source, sourceName),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        // Expanded windows keep the same player column but beside a queue panel.
        // SongScreen supplies the queue; previews leave it empty.
        PlayerBody(
            state = state,
            actions = actions,
            backStack = backStack,
            showLyrics = showLyrics,
            onToggleLyrics = { showLyrics = !showLyrics },
            modifier = Modifier.fillMaxSize().padding(padding),
            queue = queue,
        )
    }
}

/**
 * The phone player column: artwork/lyrics, song info, progress, controls.
 * Shared by the single-pane body and the Expanded player slot.
 */
@Composable
private fun PlayerBody(
    state: NowPlayingUiState,
    actions: MusicActions,
    backStack: NavBackStack<Route>,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    modifier: Modifier = Modifier,
    queue: @Composable (Modifier) -> Unit = {},
) {
    // UI States
    val songId = state.songId
    val leaving = isNavLeaving()
    val lyrics = remember(state.lyrics) { classifyLyrics(state.lyrics) }
    val currentIndex = remember(lyrics, state.positionMs) {
        (lyrics as? Lyrics.Timed)?.let { currentLyricIndex(it.lines, state.positionMs) } ?: -1
    }
    // Expanded shows the player beside the queue; compact keeps the phone column.
    if (isExpandedWidth()) {
        NowPlayingWideLayout(
            player = { playerModifier ->
                PlayerColumn(
                    state = state,
                    actions = actions,
                    backStack = backStack,
                    songId = songId,
                    leaving = leaving,
                    lyrics = lyrics,
                    currentIndex = currentIndex,
                    showLyrics = showLyrics,
                    onToggleLyrics = onToggleLyrics,
                    modifier = playerModifier,
                )
            },
            queue = queue,
            modifier = modifier,
        )
        return
    }
    PlayerColumn(
        state = state,
        actions = actions,
        backStack = backStack,
        songId = songId,
        leaving = leaving,
        lyrics = lyrics,
        currentIndex = currentIndex,
        showLyrics = showLyrics,
        onToggleLyrics = onToggleLyrics,
        modifier = modifier.padding(horizontal = 28.dp),
    )
}

/**
 * Puts this track on a television, or stops doing so.
 *
 * Talks to [CastPlayback] directly rather than through [MusicActions], the way YouPipe's player
 * does: the picker is an `ActivityResultContract`, so the launcher has to live in a composable, and
 * routing the session through the ViewModel would buy nothing but a second copy of its state.
 *
 * **All this does is open and close the session.** What plays, and where it starts from, is
 * `CastQueue`'s - it hands the current item over as the cast begins and follows the queue from then
 * on. Issuing a `PLAY_MEDIA` from here as well would make two writers of it and restart the track
 * every time this screen was composed.
 *
 * Absent entirely when Cast is not installed. An icon that only ever opened a store listing would be
 * an advertisement in the middle of a transport bar.
 */
@Composable
private fun CastButton(state: NowPlayingUiState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val castState by CastPlayback.state.collectAsState()
    val growing by CastPlayback.growing.collectAsState()

    val supported = remember { CastPlayback.support(context) == CastClient.Support.READY }
    if (!supported) return

    val song = state.song

    val picker = rememberLauncherForActivityResult(CastPickerContract()) { connected ->
        if (!connected) return@rememberLauncherForActivityResult
        scope.launch { CastPlayback.open(context) }
    }

    when (castState) {
        is CastPlayback.State.Casting -> IconButton(onClick = { CastPlayback.close() }) {
            // A track still being encoded is the window in which seeking does not work and in which a
            // failure can still appear, so it is worth showing even though playback has started.
            if (growing != null) {
                Text("\u2026")
            } else {
                IconCastConnected(tint = MaterialTheme.colorScheme.primary)
            }
        }
        CastPlayback.State.Connecting -> IconButton(onClick = {}, enabled = false) {
            Text("\u2026")
        }
        CastPlayback.State.Idle -> IconButton(
            onClick = { picker.launch(Unit) },
            // Nothing loaded means nothing to send, and a button that silently did nothing would be
            // worse than one that is plainly unavailable.
            enabled = song != null,
        ) {
            IconCast()
        }
    }
}
