package com.vayunmathur.music.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.R
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.PlaybackManager
import com.vayunmathur.music.ui.components.NewPlaylistFab
import com.vayunmathur.music.ui.components.NowPlayingBar
import com.vayunmathur.music.ui.components.TrackListItem
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Shared music components with real interactions: the mini-player's transport
 * buttons (found as the clickable ancestor of their icon's content
 * description), track rows with caller-supplied slots, and the new-playlist
 * FAB's dialog open/cancel cycle (creating would write through Room, so the
 * create path itself is device-only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicComponentsExpandedTest {

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

    private fun clickableWithIcon(description: String) =
        hasClickAction() and hasAnyAncestor(hasContentDescription(description))

    @Test
    fun nowPlayingBarTransportButtonsToggleAndSkip() {
        var toggled = 0
        var skipped = 0
        var opened = 0
        composeRule.setContent {
            NowPlayingBar(
                songId = 9L, title = "Bar Song", artist = "Bar Artist", artworkUri = null,
                isPlaying = true, owsSharedKeys = true,
                onOpen = { opened++ }, onTogglePlayPause = { toggled++ }, onSkipNext = { skipped++ },
            )
        }
        composeRule.onNodeWithText("Bar Song").assertIsDisplayed()
        // Playing state shows the Pause icon; its button toggles, Next skips.
        composeRule.onNodeWithContentDescription("Pause").performClick()
        assertEquals(1, toggled)
        composeRule.onNodeWithContentDescription("Next").performClick()
        assertEquals(1, skipped)
        assertEquals(0, opened, "transport clicks must not open the full player")
    }

    @Test
    fun nowPlayingBarPausedShowsPlayAndBodyTapOpens() {
        var toggled = 0
        var opened = 0
        composeRule.setContent {
            NowPlayingBar(
                songId = null, title = "Paused Song", artist = "Singer", artworkUri = null,
                isPlaying = false, owsSharedKeys = false,
                onOpen = { opened++ }, onTogglePlayPause = { toggled++ }, onSkipNext = {},
            )
        }
        composeRule.onNodeWithContentDescription("Play").performClick()
        assertEquals(1, toggled)
        composeRule.onNodeWithText("Paused Song").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun trackListItemRendersSlotsAndReportsClicks() {
        var clicks = 0
        composeRule.setContent {
            TrackListItem(
                title = "Slotted Song", isPlaying = false,
                artUri = "content://media/1".toUri(),
                onClick = { clicks++ },
                leading = { Text("3") },
                trailing = { Text("3:42") },
            )
        }
        composeRule.onNodeWithText("Slotted Song").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("3:42").assertIsDisplayed()
        composeRule.onNodeWithText("Slotted Song").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun newPlaylistFabOpensTheDialogAndCancelClosesItWithoutCreating() {
        val vm = viewModel()
        composeRule.setContent { NewPlaylistFab(vm) }
        composeRule.onNodeWithContentDescription("Add").performClick()
        composeRule.onNodeWithText(str(R.string.dialog_create_playlist)).assertIsDisplayed()
        composeRule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        composeRule.onNodeWithText(str(R.string.dialog_create_playlist)).assertDoesNotExist()
    }
}
