package com.vayunmathur.maps

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun settingsLabel(): String = context.getString(com.vayunmathur.library.ui.R.string.settings)

    private fun savedPlacesTitle(): String = context.getString(R.string.saved_places_title)

    @org.junit.Before
    fun setUp() {
        // The manifest disables WorkManager's auto-initializer, so nothing
        // initializes it under Robolectric and syncOverlaySchedule() would
        // throw on every activity launch. Manual init keeps the enqueue
        // synchronous and hermetic.
        runCatching {
            androidx.work.WorkManager.initialize(
                context,
                androidx.work.Configuration.Builder()
                    .setMinimumLoggingLevel(android.util.Log.DEBUG)
                    .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
                    .build(),
            )
        }
        // Satisfy the download gate: the single-archive `.mamaps` carries the
        // basemap, and the gate only checks presence on disk (plus verified
        // markers, which plain Triple specs don't require).
        context.getExternalFilesDir(null)!!.resolve("basemap.mamaps").writeBytes(ByteArray(64))
        // Satisfy the permission gate: both runtime permissions granted, so
        // the wall never appears and Navigation() hosts the map.
        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        shadowApp.grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            "android.permission.POST_NOTIFICATIONS",
        )
    }

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
        assertNotNull(composeRule.activity.intentLauncher)
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
