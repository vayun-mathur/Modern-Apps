package com.vayunmathur.maps.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.util.MapOverlayWorker
import com.vayunmathur.maps.util.MapSettingsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1/3 second pass (maps): [MapSettingsPage]'s overlay-status line in
 * its pending/applied/current states and the saved-places re-tap behaviour,
 * beyond the first pass's render checks.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1Pass2SettingsTest {

    @get:Rule val rule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun setOverlay(applied: Long, pending: Long, lastCheck: Long) = runBlocking {
        val ds = DataStoreUtils.getInstance(app)
        ds.setLong(MapOverlayWorker.KEY_APPLIED_SEQ, applied)
        ds.setLong(MapOverlayWorker.KEY_PENDING_BYTES, pending)
        ds.setLong(MapOverlayWorker.KEY_LAST_CHECK, lastCheck)
    }

    private fun awaitText(text: String, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var found = false
        while (!found && System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            found = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
            if (!found) Thread.sleep(50)
        }
        assertTrue(found, "never saw text: $text")
    }

    @Test
    fun `overlay status line covers pending applied and current`() {
        setOverlay(applied = 7, pending = 5_000_000, lastCheck = 99)
        val vm = MapSettingsViewModel(app)
        val stack = NavBackStack(arrayOf<Route>(Route.SettingsPage))
        rule.setContent { MapSettingsPage(backStack = stack, viewModel = vm) }
        rule.waitForIdle()
        rule.onNodeWithText("Check for map updates").performScrollTo().assertIsDisplayed()
        awaitText("5 MB update downloading")

        setOverlay(applied = 7, pending = 0, lastCheck = 99)
        awaitText("Map data update 7 installed")

        setOverlay(applied = 0, pending = 0, lastCheck = 99)
        awaitText("Map data is up to date")

        // leave the shared store as found for other tests
        setOverlay(applied = 0, pending = 0, lastCheck = 0)
    }

    @Test
    fun `saved places row replaces instead of stacking`() {
        val vm = MapSettingsViewModel(app)
        val stack = NavBackStack(arrayOf<Route>(Route.SettingsPage))
        rule.setContent { MapSettingsPage(backStack = stack, viewModel = vm) }
        rule.waitForIdle()
        rule.onNodeWithText("Saved places").performScrollTo().performClick()
        assertEquals(Route.SavedPlacesPage, stack.backStack.last())
        val sizeAfterFirst = stack.backStack.size
        rule.onNodeWithText("Saved places").performClick()
        assertEquals(sizeAfterFirst, stack.backStack.size)
        assertEquals(Route.SavedPlacesPage, stack.backStack.last())
    }

    @Test
    fun `voice switch round-trips through the view model`() {
        val vm = MapSettingsViewModel(app)
        val originalVoice = vm.voiceGuidance.value
        val stack = NavBackStack(arrayOf<Route>(Route.SettingsPage))
        rule.setContent { MapSettingsPage(backStack = stack, viewModel = vm) }
        rule.waitForIdle()
        rule.onNodeWithText("Voice guidance").performScrollTo().performClick()
        // the write goes through the VM's DataStore flow, so poll for the flip
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.voiceGuidance.value == originalVoice && System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            Thread.sleep(50)
        }
        assertEquals(!originalVoice, vm.voiceGuidance.value)
        vm.setVoiceGuidance(originalVoice)
        val restoreDeadline = System.currentTimeMillis() + 10_000
        while (vm.voiceGuidance.value != originalVoice && System.currentTimeMillis() < restoreDeadline) {
            rule.waitForIdle()
            Thread.sleep(50)
        }
        assertEquals(originalVoice, vm.voiceGuidance.value)
    }
}
