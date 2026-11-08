package com.vayunmathur.maps.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.MapPreferences
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The place mask: selecting a place shows its name plus an explicit
 * "Show details" action, and nothing else, until the user taps through.
 * The Google enrichment ([SelectedFeatureViewModel.currentPoiInfo]) stays
 * parked at null over the same window, so no place-details fetch starts
 * passively on selection.
 */
@RunWith(AndroidJUnit4::class)
class PlaceMaskTest {

    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun place(name: String = "Blue Bottle") = SpecificFeature.GenericPlace(
        name = name,
        phone = "555-1234",
        website = null,
        openingHours = null,
        position = GeoPoint(-122.4, 37.8),
    )

    @Test
    fun `masked header shows the name and the reveal action only`() {
        rule.setContent {
            PlaceSheetHeader(
                panelState = PlacePanelState(detailsRevealed = false),
                panelActions = PlacePanelActions.Noop,
                inactiveNavigation = null,
                feature = place(),
                requestDirections = {},
            )
        }
        rule.onNodeWithText("Blue Bottle").assertIsDisplayed()
        rule.onNodeWithText("Show details").assertIsDisplayed()
        rule.onNodeWithText("Directions").assertDoesNotExist()
    }

    @Test
    fun `the reveal action drives revealDetails`() {
        var revealed = 0
        val actions = object : PlacePanelActions {
            override fun revealDetails() { revealed++ }
        }
        rule.setContent {
            PlaceSheetHeader(
                panelState = PlacePanelState(detailsRevealed = false),
                panelActions = actions,
                inactiveNavigation = null,
                feature = place(),
                requestDirections = {},
            )
        }
        rule.onNodeWithText("Show details").performClick()
        assertEquals(1, revealed)
    }

    @Test
    fun `revealed header shows the action row`() {
        rule.setContent {
            PlaceSheetHeader(
                panelState = PlacePanelState(detailsRevealed = true),
                panelActions = PlacePanelActions.Noop,
                inactiveNavigation = null,
                feature = place(),
                requestDirections = {},
            )
        }
        rule.onNodeWithText("Show details").assertDoesNotExist()
        rule.onNodeWithText("Directions").assertIsDisplayed()
    }

    @Test
    fun `a selection opens masked and one tap reveals the actions`() {
        val selected = SelectedFeatureViewModel(app)
        val savedVm = SavedPlacesViewModel(app)
        val feature = place()
        rule.runOnIdle { selected.set(feature) }
        rule.setContent {
            PlaceSheetHeader(
                viewModel = selected,
                savedPlacesViewModel = savedVm,
                inactiveNavigation = null,
                feature = feature,
                requestDirections = {},
            )
        }
        rule.onNodeWithText("Show details").assertIsDisplayed()
        rule.onNodeWithText("Directions").assertDoesNotExist()
        rule.onNodeWithText("Show details").performClick()
        rule.onNodeWithText("Directions").assertIsDisplayed()
    }

    @Test
    fun `the reveal resets on every new selection`() {
        val selected = SelectedFeatureViewModel(app)
        rule.runOnIdle {
            selected.set(place("First"))
            assertFalse(selected.detailsRevealed.value)
            selected.revealDetails()
            assertTrue(selected.detailsRevealed.value)
            selected.set(place("Second"))
            assertFalse(selected.detailsRevealed.value)
        }
    }

    @Test
    fun `enrichment stays parked until reveal with services on`() {
        // The toggle is on, so the reveal gate is the only thing holding the
        // enrichment back. Restored afterwards: other suites assume the default
        // (off) and share the test app's DataStore file.
        val store = DataStoreUtils.getInstance(app)
        runBlocking { store.setBoolean(MapPreferences.KEY_GOOGLE_SERVICES, true) }
        try {
            val selected = SelectedFeatureViewModel(app)
            var secondEmissionArrived = false
            var flowError: Throwable? = null
            var windowElapsed = false
            rule.setContent {
                LaunchedEffect(selected) {
                    try {
                        withTimeoutOrNull(2_000) {
                            selected.currentPoiInfo.drop(1).first()
                            secondEmissionArrived = true
                        }
                    } catch (e: Throwable) {
                        flowError = e
                    } finally {
                        windowElapsed = true
                    }
                }
            }
            rule.runOnIdle { selected.set(place()) }
            rule.waitUntil(10_000) { windowElapsed }
            assertNull(flowError)
            assertFalse(secondEmissionArrived)
            assertFalse(selected.detailsRevealed.value)
        } finally {
            runBlocking { store.setBoolean(MapPreferences.KEY_GOOGLE_SERVICES, false) }
        }
    }

    @Test
    fun `the panel state carries the reveal through the shared binder`() {
        val selected = SelectedFeatureViewModel(app)
        val savedVm = SavedPlacesViewModel(app)
        rule.runOnIdle { selected.set(place()) }
        var revealed: Boolean? = null
        rule.setContent {
            revealed = com.vayunmathur.maps.util.rememberPlacePanelState(selected, savedVm).detailsRevealed
        }
        rule.waitForIdle()
        assertEquals(false, revealed)
        rule.runOnIdle { selected.revealDetails() }
        rule.waitUntil(10_000) { revealed == true }
    }
}
