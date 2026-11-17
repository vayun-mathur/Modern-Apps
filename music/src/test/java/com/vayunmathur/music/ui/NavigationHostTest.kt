package com.vayunmathur.music.ui

import android.app.Application
import android.content.ComponentName
import android.os.Binder
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.FakeMediaProvider
import com.vayunmathur.music.Navigation
import com.vayunmathur.music.R
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.data.TestMusicRepository
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.PlaybackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The real [Navigation] host over a populated library. Every tab content
 * and detail screen already had direct render tests, but the host itself
 * - its entry wiring, tab resets and the refresh kickoff - never ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationHostTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var repo: MusicRepository

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        FakeMediaProvider.enabled = true
        FakeAndroidKeyStore.install()
        ShadowContentResolver.registerProviderInternal("media", FakeMediaProvider())
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, com.vayunmathur.music.service.PlaybackService::class.java), Binder(),
        )
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        val (r, _) = TestMusicRepository.install(app)
        repo = r
        runBlocking { repo.refresh() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After fun tearDown() {
        Dispatchers.resetMain()
        FakeMediaProvider.enabled = false
    }

    private fun vm() = MusicViewModel(app, repo, PlaybackManager.getInstance(app))

    private fun awaitText(text: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            if (composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(50)
        }
        composeRule.onNodeWithText(text, substring = true).assertExists()
    }

    @Test fun navigationHostRendersTabsAndSwitchesEntries() {
        composeRule.setContent { Navigation(vm()) }
        // Home (songs) entry, refreshed by the host's own kickoff.
        awaitText("Alpha Song")
        // Each tab label switches to that tab's entry in the same host.
        composeRule.onNodeWithText(app.getString(R.string.label_albums)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app.getString(R.string.nav_artists)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app.getString(R.string.nav_playlists)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app.getString(com.vayunmathur.library.ui.R.string.home)).performClick()
        awaitText("Alpha Song")
        assertTrue(
            composeRule.onAllNodesWithText("Alpha Song").fetchSemanticsNodes().isNotEmpty(),
            "returning home must show the library again",
        )
    }

}
