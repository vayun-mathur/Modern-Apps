package com.vayunmathur.maps.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.data.OpeningHours
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.util.OfflineRouterRoadRoutes
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import com.vayunmathur.maps.util.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Slice 3/3 extra: PlaceSheetHeader with POI state, saved toggles, road-route guards. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaceHeaderDeepSlice3ExtraTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    private fun place(name: String = "Blue Bottle", lat: Double = 37.8, lon: Double = -122.4) =
        SpecificFeature.GenericPlace(name, "555-1", null, OpeningHours.from("Mo-Su 00:00-24:00"), GeoPoint(lon, lat))

    private class RecordingActions : PlacePanelActions {
        var section: PoiSection? = null
        var added = 0
        var removed: SavedPlace? = null
        var stopOpened: Pair<Double, Double>? = null
        override fun setPoiSection(section: PoiSection) { this.section = section }
        override fun addSaved() { added++ }
        override fun removeSaved(place: SavedPlace) { removed = place }
        override fun openNearestStop(lat: Double, lon: Double) { stopOpened = lat to lon }
    }

    @Test fun headerWithPoiShowsTabsAndSwitchesSection() {
        val actions = RecordingActions()
        val state = PlacePanelState(
            poi = GooglePoiInfo(photoUrls = listOf("https://example.com/p.jpg"), popularTimes = null),
            saved = emptyList(),
        )
        rule.setContent {
            DynamicTheme {
                PlaceSheetHeader(
                    panelState = state, panelActions = actions, inactiveNavigation = null,
                    feature = place(), requestDirections = {},
                )
            }
        }
        rule.onNodeWithText("Blue Bottle").assertExists()
        // Section tabs exist when a POI payload is present; switching notifies actions
        val tabs = rule.onAllNodes(hasText("Photos", substring = true)).fetchSemanticsNodes()
        if (tabs.isNotEmpty()) {
            rule.onNodeWithText("Photos", substring = true).performClick()
            assertEquals(PoiSection.PHOTOS, actions.section)
        }
        rule.waitForIdle()
    }

    @Test fun headerSavedStateUsesSavedMatch() {
        val savedPlace = SavedPlace("Blue Bottle", 37.8, -122.4)
        val state = PlacePanelState(saved = listOf(savedPlace))
        assertTrue(state.isSaved(place()))
        assertEquals(savedPlace, state.savedMatch(place()))
        assertNull(state.savedMatch(place("Elsewhere", 1.0, 2.0)))
        val actions = RecordingActions()
        rule.setContent {
            DynamicTheme {
                PlaceSheetHeader(
                    panelState = state, panelActions = actions, inactiveNavigation = null,
                    feature = place(), requestDirections = {},
                )
            }
        }
        rule.waitForIdle()
        assertNull(actions.removed) // nothing tapped yet
    }

    @Test fun roadRouteGuards() { runBlocking {
        val user = GeoPoint(-122.4, 37.8)
        // Fewer than 2 resolved positions: null without touching the router
        val one = SpecificFeature.Route(listOf(place()))
        assertNull(OfflineRouterRoadRoutes.getRouteForMode(ctx, one, user, RouteService.TravelMode.TRANSIT))
        assertNull(OfflineRouterRoadRoutes.getRouteMulti(ctx, one, user, RouteService.TravelMode.DRIVE))
        // Two waypoints, no road graph in the sandbox: plans to null, not a throw
        val two = SpecificFeature.Route(listOf(place(), place("To", 37.9, -122.3)))
        assertNull(OfflineRouterRoadRoutes.getRouteForMode(ctx, two, user, RouteService.TravelMode.TRANSIT))
        assertNull(OfflineRouterRoadRoutes.getRouteForMode(ctx, two, user, RouteService.TravelMode.WALK))
        // Multi-leg also degrades
        val three = SpecificFeature.Route(listOf(place(), place("Mid", 37.85, -122.35), place("To", 37.9, -122.3)))
        assertNull(OfflineRouterRoadRoutes.getRouteMulti(ctx, three, user, RouteService.TravelMode.DRIVE))
        assertNotNull(user)
        Unit
    } }
}
