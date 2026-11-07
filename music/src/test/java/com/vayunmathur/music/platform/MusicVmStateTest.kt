package com.vayunmathur.music.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.ui.AlbumDetailScreen
import com.vayunmathur.music.ui.HomeTabContent
import com.vayunmathur.music.ui.components.PlayingBottomBar
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The ViewModel's composable state accessors and the screens bound directly
 * to them, against the real (empty) library: every accessor must resolve to
 * "nothing here" rather than crash before the first MediaStore scan lands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicVmStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        // Robolectric binds no real PlaybackService, so the MediaController's
        // service-connected callback would NPE on a null ComponentName when
        // the main looper idles. Registering the component keeps that
        // callback on media3's ordinary "session unreachable" path.
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            android.content.ComponentName(app, com.vayunmathur.music.service.PlaybackService::class.java),
            android.os.Binder(),
        )
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun viewModel(): MusicViewModel =
        MusicViewModel(app, MusicRepository.get(app), PlaybackManager.getInstance(app))

    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home))

    @Test
    fun `now playing state is null without a loaded item`() {
        val vm = viewModel()
        var captured: NowPlayingUiState? = NowPlayingUiState(title = "sentinel")
        composeRule.setContent { captured = vm.nowPlayingState() }
        assertNull(captured)
    }

    @Test
    fun `entity state accessors resolve to null for unknown ids`() {
        val vm = viewModel()
        var music: Any? = "x"
        var album: Any? = "x"
        var artist: Any? = "x"
        composeRule.setContent {
            val m by vm.musicState(99L)
            val a by vm.albumState(99L)
            val r by vm.artistState(99L)
            music = m; album = a; artist = r
        }
        assertNull(music); assertNull(album); assertNull(artist)
    }

    @Test
    fun `playing song id composable is null without playback`() {
        val vm = viewModel()
        var captured: Long? = 42L
        composeRule.setContent { captured = vm.playingSongIdFrom("album_7") }
        assertNull(captured)
    }

    @Test
    fun `home tab renders the songs screen over an empty library`() {
        val vm = viewModel()
        composeRule.setContent { HomeTabContent(stack(), vm) }
        composeRule.onRoot().assertExists()
    }

    @Test
    fun `album detail renders nothing for an unknown album`() {
        val vm = viewModel()
        composeRule.setContent { AlbumDetailScreen(stack(), vm, albumId = 12345L) }
        composeRule.onRoot().assertExists()
        composeRule.onNodeWithText("Great Album").assertDoesNotExist()
    }

    @Test
    fun `playing bottom bar renders nothing without a loaded item`() {
        val vm = viewModel()
        composeRule.setContent {
            PlayingBottomBar(vm, stack())
            androidx.compose.material3.Text("anchor")
        }
        composeRule.onNodeWithText("anchor").assertIsDisplayed()
    }
}
