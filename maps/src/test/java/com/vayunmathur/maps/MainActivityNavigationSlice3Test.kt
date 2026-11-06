package com.vayunmathur.maps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * `MainActivity.kt` (the [Route] destinations and their entries).
 *
 * The map surface degrades without the native renderer (see `MapNative`:
 * unavailable renders the background colour), so the full hierarchy —
 * map list pane plus settings / saved-places detail entries — composes
 * under Robolectric and the overflow + settings-row actions drive the
 * shared back stack exactly as on device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityNavigationSlice3Test {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun settingsLabel(): String = context.getString(com.vayunmathur.library.ui.R.string.settings)

    private fun savedPlacesTitle(): String = context.getString(R.string.saved_places_title)

    @Test
    fun `navigation starts on the map with a settings overflow`() {
        composeRule.setContent {
            Navigation()
        }
        // The map's top bar carries the settings overflow action.
        composeRule.onNodeWithContentDescription(settingsLabel()).assertIsDisplayed()
    }

    @Test
    fun `settings overflow opens the settings page`() {
        composeRule.setContent {
            Navigation()
        }
        composeRule.onNodeWithContentDescription(settingsLabel()).performClick()
        // The settings entry renders its display section.
        composeRule.onNodeWithText(context.getString(R.string.settings_section_display)).assertIsDisplayed()
    }

    @Test
    fun `settings page links through to saved places`() {
        composeRule.setContent {
            Navigation()
        }
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
}
