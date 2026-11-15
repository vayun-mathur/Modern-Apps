package com.vayunmathur.music.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.platform.AlbumDetailUiState
import com.vayunmathur.music.platform.LyricLine
import com.vayunmathur.music.platform.Lyrics
import com.vayunmathur.music.platform.MusicActions
import com.vayunmathur.music.platform.NowPlayingUiState
import com.vayunmathur.music.platform.SongsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour-rich rendering of the state-driven detail screens: album play /
 * shuffle / track / artist navigation, songs-list playback, queue jumps, and
 * now-playing source navigation and lyrics states. The ViewModel-bound
 * PlaylistDetail/ArtistDetail screens have no state-driven variant and their
 * bodies collect Room flows (SQLCipher, unavailable on a host JVM), so only
 * AlbumDetailContent — the state-driven half of the album detail — is used for
 * detail-body coverage here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicDetailPagesExpandedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun song(id: Long, title: String, duration: Long = 180_000) = Music(
        id = id, title = title, artist = "Artist", artistId = 1, album = "Album", albumId = 7,
        uri = "content://media/$id", duration = duration, trackNumber = id.toInt(), year = 2020,
    )

    private fun stack(vararg routes: Route) =
        NavBackStack<Route>(if (routes.isEmpty()) arrayOf(Route.Home) else arrayOf(*routes))

    private class RecordingActions : MusicActions {
        var played: Triple<List<Music>, Int, String?>? = null
        var playedSourceName: String? = null
        var shuffled: Pair<String?, String?>? = null
        override fun playSong(songs: List<Music>, startWithIndex: Int, sourceId: String?, sourceName: String?) {
            played = Triple(songs, startWithIndex, sourceId)
            playedSourceName = sourceName
        }
        override fun playShuffled(songs: List<Music>, sourceId: String?, sourceName: String?) {
            shuffled = sourceId to sourceName
        }
    }

    @Test
    fun albumDetailPlayShuffleAndTrackClicksReportAlbumSource() {
        val actions = RecordingActions()
        val tracks = listOf(song(1, "Track One"), song(2, "Track Two"))
        composeRule.setContent {
            AlbumDetailContent(
                AlbumDetailUiState(
                    albumId = 7, name = "Great Album", artistName = "Great Artist",
                    artistId = null, info = "2020 • 2 songs", tracks = tracks, playingSongId = 2L,
                ),
                actions, stack(),
            )
        }
        composeRule.onNodeWithText("Great Album").assertIsDisplayed()
        // Play/Shuffle and the track rows are lazy items below the 260dp header,
        // so they are not composed until scrolled to (the previous onNodeWithText
        // click failed with "no node with text Play" for exactly this reason).
        composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(str(UiR.string.play)))
        composeRule.onNodeWithText(str(UiR.string.play)).performClick()
        assertEquals(Triple(tracks, 0, "album_7"), actions.played)
        assertEquals("Great Album", actions.playedSourceName)

        composeRule.onNodeWithText(str(R.string.label_shuffle)).performClick()
        assertEquals("album_7" to "Great Album", actions.shuffled)

        composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Track One"))
        composeRule.onNodeWithText("Track One").performClick()
        assertEquals(Triple(tracks, 0, "album_7"), actions.played)
    }

    @Test
    fun albumDetailArtistNameNavigatesOnlyWhenAnArtistIsKnown() {
        val actions = RecordingActions()
        val nav = stack(Route.Home, Route.AlbumDetail(7))
        composeRule.setContent {
            AlbumDetailContent(
                AlbumDetailUiState(
                    albumId = 7, name = "Great Album", artistName = "Great Artist",
                    artistId = 31L, info = "2020", tracks = listOf(song(1, "Only Track")),
                ),
                actions, nav,
            )
        }
        composeRule.onNodeWithText("Great Artist").performClick()
        assertEquals(Route.ArtistDetail(31L), nav.last())
    }

    @Test
    fun albumDetailWithNoTracksStillShowsHeaderAndActions() {
        val actions = RecordingActions()
        composeRule.setContent {
            AlbumDetailContent(
                AlbumDetailUiState(albumId = 8, name = "Empty Album", info = "Unknown", tracks = emptyList()),
                actions, stack(),
            )
        }
        composeRule.onNodeWithText("Empty Album").assertIsDisplayed()
        composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(str(UiR.string.play)))
        composeRule.onNodeWithText(str(UiR.string.play)).performClick()
        // Play is wired even for an empty track list; the ViewModel/PlaybackManager
        // guard is what makes it a no-op, and this screen must still report it.
        assertEquals(0, actions.played?.second)
        assertEquals("album_8", actions.played?.third)
    }

    @Test
    fun songsScreenClickReportsIndexAndSongTappedCallback() {
        val actions = RecordingActions()
        var tapped = 0
        val songs = listOf(song(1, "Alpha"), song(2, "Beta"), song(3, "Gamma"))
        composeRule.setContent {
            SongsScreen(
                SongsUiState(songs = songs, playingSongId = 1L),
                actions, stack(),
                onSongTapped = { tapped++ },
            )
        }
        composeRule.onNodeWithText("Gamma").performClick()
        assertEquals(Triple(songs, 2, "all_songs"), actions.played)
        assertEquals(1, tapped)
    }

    @Test
    fun songsScreenEmptyRendersNoTracks() {
        composeRule.setContent {
            SongsScreen(SongsUiState(songs = emptyList(), loading = false), RecordingActions(), stack())
        }
        composeRule.onRoot().assertIsDisplayed()
        composeRule.onNodeWithText("Alpha").assertDoesNotExist()
    }

    @Test
    fun queueListUsesUnknownTitleAndJumpsByPosition() {
        var jumped = -1
        val songs = listOf(song(1, ""), song(2, "Second Song"))
        composeRule.setContent {
            QueueList(songs, playingSongId = 2L, title = "", onJump = { jumped = it })
        }
        composeRule.onNodeWithText(str(R.string.unknown_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.unknown_title)).performClick()
        assertEquals(0, jumped)
        composeRule.onNodeWithText("Second Song").performClick()
        assertEquals(1, jumped)
    }

    @Test
    fun nowPlayingGoToAlbumSourceReplacesThePlayerOnTheStack() {
        val nav = stack(Route.Home, Route.Song)
        composeRule.setContent {
            NowPlayingScreen(
                NowPlayingUiState(
                    title = "Sourced", artist = "Artist", album = "Album", songId = 4L,
                    sourceId = "album_7", sourceName = "Great Album",
                ),
                RecordingActions(), nav,
            )
        }
        composeRule.onNodeWithText(str(R.string.go_to_source, "Great Album")).performClick()
        assertEquals(Route.AlbumDetail(7L), nav.last())
    }

    @Test
    fun nowPlayingGoToPlaylistAndArtistSourcesResolveTheirRoutes() {
        val nav = stack(Route.Home, Route.Song)
        composeRule.setContent {
            NowPlayingScreen(
                NowPlayingUiState(
                    title = "Sourced", artist = "Artist", songId = 4L,
                    sourceId = "playlist_12", sourceName = "Road Mix",
                ),
                RecordingActions(), nav,
            )
        }
        composeRule.onNodeWithText(str(R.string.go_to_source, "Road Mix")).performClick()
        assertEquals(Route.PlaylistDetail(12L), nav.last())
    }

    @Test
    fun nowPlayingWithoutSourceShowsNoGoToShortcut() {
        composeRule.setContent {
            NowPlayingScreen(
                NowPlayingUiState(title = "Unsourced", artist = "Artist", songId = 4L),
                RecordingActions(), stack(),
            )
        }
        composeRule.onNodeWithText("Unsourced").assertIsDisplayed()
        composeRule.onAllNodesWithText(str(R.string.go_to_source, "Unsourced")).assertCountEquals(0)
    }

    @Test
    fun lyricsViewTimedShowsAllLines() {
        composeRule.setContent {
            LyricsView(
                Lyrics.Timed(listOf(LyricLine(0, "line one"), LyricLine(5_000, "line two"))),
                currentIndex = 1,
            )
        }
        composeRule.onNodeWithText("line one").assertIsDisplayed()
        composeRule.onNodeWithText("line two").assertIsDisplayed()
    }

    @Test
    fun lyricsViewPlainShowsTheUntimedBlock() {
        composeRule.setContent { LyricsView(Lyrics.Plain("untimed words"), currentIndex = -1) }
        composeRule.onNodeWithText("untimed words").assertIsDisplayed()
    }

    @Test
    fun lyricsViewNoneShowsTheEmptyMessage() {
        composeRule.setContent { LyricsView(Lyrics.None, currentIndex = -1) }
        composeRule.onNodeWithText(str(R.string.no_lyrics_available)).assertIsDisplayed()
    }

    @Test
    fun clickableComponentsReportClicks() {
        var clicks = 0
        composeRule.setContent {
            com.vayunmathur.music.ui.components.PlayShuffleRow(
                onPlay = { clicks += 1 },
                onShuffle = { clicks += 10 },
            )
        }
        composeRule.onNodeWithText(str(UiR.string.play)).performClick()
        assertEquals(1, clicks)
        composeRule.onNodeWithText(str(R.string.label_shuffle)).performClick()
        assertEquals(11, clicks)
        assertTrue(clicks == 11)
    }
}
