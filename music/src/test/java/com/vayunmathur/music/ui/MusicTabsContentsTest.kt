package com.vayunmathur.music.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.PlaybackManager
import com.vayunmathur.music.ui.components.AddToPlaylistButton
import com.vayunmathur.music.ui.components.MusicTabsBar
import com.vayunmathur.music.ui.components.ShufflePlayFab
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tab host's pieces over an empty library. MusicTabsScreen itself is not
 * rendered here: its LaunchedEffect calls refreshLibrary(), whose tag backfill
 * opens the SQLCipher cache (unavailable on a host JVM), and enqueues a
 * WorkManager SyncWorker (uninitialised under Robolectric) — both device-only.
 * Its bar and tab contents, which carry the actual tab behaviour, are.
 * PlaylistsTabContent is likewise omitted: it collects the Room playlist flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicTabsContentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        val app = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            android.content.ComponentName(app, com.vayunmathur.music.service.PlaybackService::class.java),
            android.os.Binder(),
        )
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int): String = context.getString(id)

    private fun viewModel(): MusicViewModel =
        MusicViewModel(app, MusicRepository.get(app), PlaybackManager.getInstance(app))

    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home))

    private fun song(id: Long) = Music(
        id = id, title = "Song $id", artist = "Artist", artistId = 1, album = "Album",
        albumId = 7, uri = "content://media/$id", duration = 180_000, trackNumber = 1, year = 2020,
    )

    @Test
    fun tabsBarReportsEveryTabSelection() {
        val selected = mutableListOf<Int>()
        composeRule.setContent { MusicTabsBar(selectedTab = 0, onSelectTab = { selected += it }) }
        composeRule.onNodeWithText(str(R.string.label_albums)).performClick()
        composeRule.onNodeWithText(str(R.string.nav_artists)).performClick()
        composeRule.onNodeWithText(str(R.string.nav_playlists)).performClick()
        composeRule.onNodeWithText(str(UiR.string.home)).performClick()
        assertEquals(listOf(1, 2, 3, 0), selected)
    }

    @Test
    fun albumsTabRendersOverAnEmptyLibrary() {
        val vm = viewModel()
        composeRule.setContent { AlbumsTabContent(stack(), vm) }
        composeRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun artistsTabRendersOverAnEmptyLibrary() {
        val vm = viewModel()
        composeRule.setContent { ArtistsTabContent(stack(), vm) }
        composeRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun shuffleFabAppearsForSongsAndReportsTheClick() {
        var shuffles = 0
        composeRule.setContent {
            ShufflePlayFab(listOf(song(1), song(2))) { shuffles++ }
        }
        composeRule.onAllNodes(hasClickAction())[0].performClick()
        assertEquals(1, shuffles)
    }

    @Test
    fun addToPlaylistButtonPushesTheDialogRouteForTheSong() {
        val nav = stack()
        composeRule.setContent { AddToPlaylistButton(nav, song(42)) }
        composeRule.onAllNodes(hasClickAction())[0].performClick()
        assertEquals(Route.AddToPlaylistDialog(42L), nav.last())
    }
}
