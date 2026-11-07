package com.vayunmathur.music.data

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.FakeMediaProvider
import com.vayunmathur.music.Route
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.PlaybackManager
import com.vayunmathur.music.platform.getAlbums
import com.vayunmathur.music.platform.getArtists
import com.vayunmathur.music.platform.getSongs
import com.vayunmathur.music.ui.HomeTabContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The populated half of the library: a fake media provider serves three
 * songs, and the MediaStore readers, the repository refresh and the
 * ViewModel's per-entity state all have to agree about them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PopulatedLibraryTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        ShadowContentResolver.registerProviderInternal("media", FakeMediaProvider())
        val app = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            android.content.ComponentName(
                app,
                com.vayunmathur.music.service.PlaybackService::class.java,
            ),
            android.os.Binder(),
        )
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun viewModel(): MusicViewModel =
        MusicViewModel(
            ApplicationProvider.getApplicationContext(),
            MusicRepository.get(context),
            PlaybackManager.getInstance(context),
        )

    @Test
    fun `media store readers parse the library`() { runBlocking {
        val songs = getSongs(context)
        assertEquals(listOf(1L, 2L, 3L), songs.map { it.id })
        val beta = songs.first { it.id == 1L }
        assertEquals("Beta Song", beta.title)
        assertEquals(1, beta.discNumber)
        assertEquals(2, beta.trackNumber)
        assertEquals(2019, beta.year)
        assertEquals("content://media/external/audio/media/1", beta.uri)
        val alpha = songs.first { it.id == 2L }
        // TRACK 2005 is MediaStore for disc 2, track 5; a missing year stays 0.
        assertEquals(2, alpha.discNumber)
        assertEquals(5, alpha.trackNumber)
        assertEquals(0, alpha.year)

        assertEquals(listOf("Album One", "Album Two"), getAlbums(context).map { it.name })
        val artists = getArtists(context)
        assertEquals("Artist One", artists.first { it.id == 11L }.name)
        // The <unknown> sentinel normalises to empty so screens can localise it.
        assertEquals("", artists.first { it.id == 12L }.name)
    }

    } }

    @Test
    fun `repository refresh publishes the fake library`() { runBlocking {
        val repo = MusicRepository.get(context)
        repo.refresh()
        assertEquals(3, repo.getAllMusic().size)
        assertEquals(2, repo.getAllAlbums().size)
        assertEquals(2, repo.getAllArtists().size)
        assertTrue(repo.loaded.first())
    }

    } }

    @Test
    fun `view model entity states find the fake songs`() { runBlocking {
        MusicRepository.get(context).refresh()
        val vm = viewModel()
        var title: String? = null
        var albumName: String? = null
        var artistName: String? = null
        composeRule.setContent {
            val song by vm.musicState(2L)
            val album by vm.albumState(21L)
            val artist by vm.artistState(11L)
            title = song?.title
            albumName = album?.name
            artistName = artist?.name
        }
        assertEquals("Alpha Song", title)
        assertEquals("Album One", albumName)
        assertEquals("Artist One", artistName)
    }

    } }

    @Test
    fun `home tab lists the fake songs and a tap starts playback bookkeeping`() { runBlocking {
        MusicRepository.get(context).refresh()
        val vm = viewModel()
        val stack = NavBackStack<Route>(arrayOf(Route.Home))
        composeRule.setContent { HomeTabContent(stack, vm) }
        composeRule.onNodeWithText("Alpha Song").assertIsDisplayed()
        composeRule.onNodeWithText("Beta Song").assertIsDisplayed()
        composeRule.onNodeWithText("Gamma Song").assertIsDisplayed()
        composeRule.onNodeWithText("Beta Song").performClick()
        composeRule.runOnIdle { }
        assertNotNull(vm.currentSource.value, "a row tap must record the queue source")
    }
    } }
}
