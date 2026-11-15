package com.vayunmathur.music.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Binder
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.FakeMediaProvider
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.data.TestMusicRepository
import com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.PlaybackManager
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ViewModel-bound detail screens ([ArtistDetailScreen], [AlbumDetailScreen],
 * [PlaylistDetailScreen]) against the populated fake library from
 * [FakeMediaProvider]: the same three songs / two albums / two artists the
 * repository tests use. Assertions wait for the MediaStore-backed flows to
 * land instead of assuming the first composition is final.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PopulatedDetailScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    companion object {
        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            FakeMediaProvider.enabled = false
            val app = ApplicationProvider.getApplicationContext<Context>()
            runBlocking { MusicRepository.get(app).refresh() }
        }
    }

    @Before
    fun setUp() {
        FakeMediaProvider.enabled = true
        FakeAndroidKeyStore.install()
        ShadowContentResolver.registerProviderInternal("media", FakeMediaProvider())
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, com.vayunmathur.music.service.PlaybackService::class.java),
            Binder(),
        )
        // In-memory Room for the playlist/matching flows these screens
        // collect (production uses SQLCipher, unavailable on the JVM).
        TestMusicRepository.install(app)
        runBlocking { MusicRepository.get(app).refresh() }
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun viewModel(): MusicViewModel =
        MusicViewModel(app, MusicRepository.get(app), PlaybackManager.getInstance(app))

    private fun stack(vararg routes: Route): NavBackStack<Route> = NavBackStack(arrayOf(*routes))

    private fun awaitText(text: String) {
        // Wall-clock poll: composeRule.waitUntil blocks the main thread the
        // ViewModel flows are confined to, so they would never emit there.
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            if (composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(50)
        }
        composeRule.onNodeWithText(text, substring = true).assertExists()
    }

    @Test
    fun artistDetailShowsArtistAlbumsAndSongsAndPlaysFromArtistSource() {
        val vm = viewModel()
        val nav = stack(Route.Home)
        composeRule.setContent { ArtistDetailScreen(nav, vm, artistId = 11L) }
        awaitText("Artist One")
        // Both songs by Artist One are listed below the header (lazy column).
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Beta Song"))
        composeRule.onNodeWithText("Alpha Song", substring = true).assertExists()
        composeRule.onNodeWithText("Beta Song", substring = true).performClick()
        // Tapping a track starts playback tagged with the artist source.
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.currentSource.value != "artist_11" && System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            Thread.sleep(50)
        }
        assertEquals("artist_11", vm.currentSource.value)
    }

    @Test
    fun artistDetailForUnknownArtistComposesNothing() {
        val vm = viewModel()
        composeRule.setContent { ArtistDetailScreen(stack(Route.Home), vm, artistId = 999L) }
        composeRule.waitForIdle()
        // The screen returns before its scaffold when the artist is absent.
        composeRule.onAllNodesWithText("Artist One").assertCountEquals(0)
        composeRule.onAllNodesWithText("Beta Song").assertCountEquals(0)
    }

    @Test
    fun albumDetailShowsAlbumTracksSortedByDiscAndTrack() {
        val vm = viewModel()
        val nav = stack(Route.Home)
        composeRule.setContent { AlbumDetailScreen(nav, vm, albumId = 21L) }
        awaitText("Album One")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Beta Song"))
        composeRule.onNodeWithText("Alpha Song", substring = true).assertExists()
        // Album One's artist is unambiguous, so the artist link routes there.
        composeRule.onNodeWithText("Artist One", substring = true).performClick()
        assertTrue(nav.last() is Route.ArtistDetail, "expected artist route, was ${nav.last()}")
        assertEquals(11L, (nav.last() as Route.ArtistDetail).artistId)
    }

    @Test
    fun albumDetailForUnknownAlbumComposesNothing() {
        val vm = viewModel()
        composeRule.setContent { AlbumDetailScreen(stack(Route.Home), vm, albumId = 999L) }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Album One").assertCountEquals(0)
    }


    @Test
    fun playlistDetailShowsPlaylistTracksAndDeleteRemovesIt() {
        val repo = MusicRepository.get(app)
        val playlistId = runBlocking {
            val id = repo.upsertPlaylist(Playlist(name = "Road Mix"))
            repo.upsertMatching(ManyManyMatching(leftID = 1, rightID = id, type = TYPE_MUSIC_PLAYLIST))
            repo.upsertMatching(ManyManyMatching(leftID = 3, rightID = id, type = TYPE_MUSIC_PLAYLIST))
            id
        }
        val vm = viewModel()
        val nav = stack(Route.Home)
        composeRule.setContent { PlaylistDetailScreen(nav, vm, playlistId = playlistId) }
        awaitText("Road Mix")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Beta Song"))
        composeRule.onNodeWithText("Beta Song", substring = true).assertExists()
        composeRule.onNodeWithText("Gamma Song", substring = true).assertExists()
    }
}
