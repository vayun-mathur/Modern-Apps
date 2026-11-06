package com.vayunmathur.maps.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.R as MapsR
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.ThemeMode
import com.vayunmathur.maps.util.MapSettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): the maps settings screen — every section renders, and
 * DataStore-backed settings round-trip through [MapSettingsViewModel].
 *
 * The ViewModel is constructed fresh per test but DataStore is a process-wide
 * singleton file, so each test restores whatever value it changes.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1SettingsComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val appContext: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun strings(id: Int, vararg args: Any): String =
        if (args.isEmpty()) appContext.getString(id) else appContext.getString(id, *args)

    private fun viewModel(): MapSettingsViewModel {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        return MapSettingsViewModel(app)
    }

    private fun page(vm: MapSettingsViewModel) {
        rule.setContent {
            MapSettingsPage(
                backStack = NavBackStack(arrayOf<Route>(Route.SettingsPage)),
                viewModel = vm,
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `sections and rows render`() {
        page(viewModel())
        // Rows above the fold.
        for (id in listOf(
            MapsR.string.settings_theme,
            MapsR.string.settings_voice_guidance,
        )) {
            rule.onNodeWithText(strings(id)).assertIsDisplayed()
        }
        // Everything else exists in the scrollable column (below the fold
        // on a short viewport — tree presence, not visibility).
        for (id in listOf(
            MapsR.string.settings_google_services,
            MapsR.string.saved_places_title,
            MapsR.string.settings_section_display,
            MapsR.string.settings_section_places,
            MapsR.string.settings_section_map_data,
        )) {
            assertTrue(
                rule.onAllNodes(hasText(strings(id))).fetchSemanticsNodes().isNotEmpty(),
                "missing node: ${strings(id)}",
            )
        }
    }

    @Test
    fun `toggling voice guidance flips the persisted value`() {
        val vm = viewModel()
        // Prime the key first: boolFlow emits nothing until DataStore has the
        // key, and re-setting the current default would emit nothing either.
        runBlocking { vm.setVoiceGuidance(true) }
        val before = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                vm.voiceGuidance.first { it }
            }
        }
        assertEquals(true, before, "priming the key must land")
        page(vm)
        rule.onNodeWithText(strings(MapsR.string.settings_voice_guidance))
            .assertIsDisplayed()
            .performClick()
        // The row writes through the ViewModel into DataStore; await the
        // flipped emission rather than sleeping on the wall clock. The
        // compose rule idles the main looper while waiting.
        var after: Boolean? = null
        val deadline = System.currentTimeMillis() + 30_000L
        while (after == null && System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            after = runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(500L) {
                    vm.voiceGuidance.first { !it }
                }
            }
        }
        assertEquals(false, after, "the toggle must flip the persisted value")
        runBlocking { vm.setVoiceGuidance(true) }
    }

    @Test
    fun `theme modes round-trip through the viewmodel`() {
        val vm = viewModel()
        assertEquals(3, ThemeMode.entries.size)
        // Prime the key first: stringFlow emits nothing until DataStore has
        // the key, and re-setting the current value would emit nothing.
        runBlocking { vm.setThemeMode(ThemeMode.SYSTEM) }
        val before = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                vm.themeMode.first { it == ThemeMode.SYSTEM }
            }
        }
        assertEquals(ThemeMode.SYSTEM, before, "priming the key must land")
        runBlocking { vm.setThemeMode(ThemeMode.DARK) }
        // stringFlow has NO default, so themeMode only emits once the write
        // lands in DataStore — await the write while idling the main looper.
        var seen: ThemeMode? = null
        val deadline = System.currentTimeMillis() + 30_000L
        while (seen == null && System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            seen = runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(500L) {
                    vm.themeMode.first { it == ThemeMode.DARK }
                }
            }
        }
        assertEquals(ThemeMode.DARK, seen)
        runBlocking { vm.setThemeMode(ThemeMode.SYSTEM) }
    }
}
