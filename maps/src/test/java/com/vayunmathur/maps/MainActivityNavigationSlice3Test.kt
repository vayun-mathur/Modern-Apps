package com.vayunmathur.maps

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 (root package files M-Z): the [Navigation] hierarchy from
 * `MainActivity.kt` (the [Route] destinations and their entries), exercised
 * through the real activity's `Content()` so the download gate, permission
 * gate, offline banner and navigation host legs all count.
 *
 * The map surface degrades without the native renderer (see `MapNative`:
 * unavailable renders the background colour), so the full hierarchy —
 * map list pane plus settings / saved-places detail entries — composes
 * under Robolectric and the overflow + settings-row actions drive the
 * shared back stack exactly as on device.
 *
 * The activity rule launches MainActivity with no VIEW intent (plain start,
 * no deep link). Each gate test plants what its leg needs: a basemap archive
 * on disk for the download gate, granted runtime permissions for the
 * permission gate. No network is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityNavigationSlice3Test {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    companion object {
        /**
         * The activity rule launches MainActivity before instance `@Before`
         * methods run, so everything its `onCreateBeforeContent()` needs must
         * be planted here: WorkManager (the manifest disables its
         * auto-initializer), the basemap archive for the download gate, and
         * the runtime grants for the permission gate.
         */
        @org.junit.BeforeClass
        @JvmStatic
        fun setUpClass() {
            val app = ApplicationProvider.getApplicationContext<android.content.Context>()
            runCatching {
                androidx.work.WorkManager.initialize(
                    app,
                    androidx.work.Configuration.Builder()
                        .setMinimumLoggingLevel(android.util.Log.DEBUG)
                        .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
                        .build(),
                )
            }
            app.getExternalFilesDir(null)!!.resolve("basemap.mamaps").writeBytes(ByteArray(64))
            org.robolectric.Shadows.shadowOf(app as android.app.Application).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                "android.permission.POST_NOTIFICATIONS",
            )
        }
    }

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun settingsLabel(): String = context.getString(com.vayunmathur.library.ui.R.string.settings)

    private fun savedPlacesTitle(): String = context.getString(R.string.saved_places_title)

    @Test
    fun `activity content starts on the map with a settings overflow`() {
        // The map's top bar carries the settings overflow action.
        composeRule.onNodeWithContentDescription(settingsLabel()).assertIsDisplayed()
    }

    @Test
    fun `settings overflow opens the settings page`() {
        composeRule.onNodeWithContentDescription(settingsLabel()).performClick()
        // The settings entry renders its display section.
        composeRule.onNodeWithText(context.getString(R.string.settings_section_display)).assertIsDisplayed()
    }

    @Test
    fun `settings page links through to saved places`() {
        composeRule.onNodeWithContentDescription(settingsLabel()).performClick()
        // The places section sits below the fold: scroll the row into view first
        // (clicking an off-screen node silently no-ops).
        composeRule.onNodeWithText(savedPlacesTitle()).performScrollTo()
        composeRule.onNodeWithText(savedPlacesTitle()).performClick()
        // The saved-places entry renders its (empty) saved section. (The title
        // text also exists on the settings row behind the detail pane, so pin
        // the destination by its unique empty-list copy.)
        composeRule.onNodeWithText(context.getString(R.string.saved_list_empty)).assertIsDisplayed()
    }

    @Test
    fun `route objects are distinct nav keys`() {
        assertEquals(3, setOf(Route.MapPage, Route.SettingsPage, Route.SavedPlacesPage).size)
    }

    @Test
    fun `activity content passes the download gate with an archive on disk`() {
        // The download screen never appears: the map chrome is up instead.
        composeRule.onNodeWithContentDescription(settingsLabel()).assertIsDisplayed()
    }

    @Test
    fun `activity content passes the permission gate when grants are held`() {
        // The permission wall never appears: its title is absent from the tree.
        composeRule.onAllNodesWithText(
            context.getString(com.vayunmathur.library.ui.R.string.permission_gate_title),
        ).assertCountEquals(0)
    }

    @Test
    fun `activity content shows the offline banner while offline`() {
        // Under Robolectric there is no connectivity, so OfflineAware's banner
        // renders above the map — pinning the OfflineAware leg, not just the
        // Navigation host.
        composeRule.onNodeWithText("No internet connection").assertIsDisplayed()
    }
}
