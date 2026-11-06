package com.vayunmathur.maps.util

import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.ThemeMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 3/3 (root package files M-Z): the `util` ViewModels' selection,
 * persistence and search-state behaviour, driven through the real classes
 * under Robolectric.
 *
 * ViewModel writes land on `viewModelScope` (the Robolectric main looper)
 * plus DataStore IO threads, so assertions on their effects [await] the
 * published value with a bounded poll rather than assuming a flush.
 */
@RunWith(AndroidJUnit4::class)
class ViewModelsSlice3Test {

    // Pumps the main looper for viewModelScope work. Plain Robolectric leaves
    // Dispatchers.Main tasks queued; raw `idle()` is unsafe here (a repeating
    // task would never let it return), so each wait step runs exactly one task.
    @get:Rule
    val composeRule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun place(name: String = "Ferry Building") = SpecificFeature.GenericPlace(
        name = name,
        phone = null,
        website = null,
        openingHours = null,
        position = GeoPoint(-122.4, 37.8),
    )

    /**
     * Runs one main-looper task per wait step so `viewModelScope` (Main)
     * work drains: plain Robolectric never idles it on its own. Bounded by
     * construction — unlike `idle()`, a repeating task cannot trap it.
     */
    private fun pumpMain() {
        runCatching { shadowOf(Looper.getMainLooper()).runOneTask() }
    }

    private fun <T> awaitState(flow: StateFlow<T>, timeoutMs: Long = 10_000, want: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: T = flow.value
        while (System.currentTimeMillis() < deadline) {
            pumpMain()
            last = flow.value
            if (want(last)) return last
            Thread.sleep(25)
        }
        pumpMain()
        return flow.value
    }

    private fun <T> awaitEquals(flow: StateFlow<T>, expected: T, timeoutMs: Long = 10_000) {
        val got = awaitState(flow, timeoutMs) { it == expected }
        assertEquals(expected, got)
    }

    @BeforeTest
    fun clearPrefs() {
        val ds = DataStoreUtils.getInstance(app)
        runBlocking {
            ds.setString("saved_places_list", "[]")
            ds.setString("saved_place_lists", "{}")
            ds.setString("parking_active_spot", "")
            ds.setString("parking_history", "[]")
            ds.setString("theme_mode", "")
        }
    }

    // --- SelectedFeatureViewModel: synchronous selection surface ---

    @Test
    fun `selection starts empty and clears`() {
        val vm = SelectedFeatureViewModel(app)
        assertNull(vm.selectedFeature.value)
        vm.set(place())
        assertEquals(place(), vm.selectedFeature.value)
        vm.set(null)
        assertNull(vm.selectedFeature.value)
    }

    @Test
    fun `selecting resets the poi section to details`() {
        val vm = SelectedFeatureViewModel(app)
        vm.setPoiSection(com.vayunmathur.maps.data.google.PoiSection.REVIEWS)
        vm.set(place())
        assertEquals(com.vayunmathur.maps.data.google.PoiSection.DETAILS, vm.poiSection.value)
    }

    @Test
    fun `selectAndFocus requests a focus for routable features`() {
        val vm = SelectedFeatureViewModel(app)
        assertNull(vm.pendingFocus.value)
        vm.selectAndFocus(place(), zoom = 15.0)
        val focus = vm.pendingFocus.value
        assertEquals(place().position, focus?.position)
        assertEquals(15.0, focus?.zoom)
        vm.consumeFocus()
        assertNull(vm.pendingFocus.value)
    }

    @Test
    fun `selectAndFocus without a position requests no focus`() {
        val vm = SelectedFeatureViewModel(app)
        vm.selectAndFocus(SpecificFeature.Admin0Label(name = "France"))
        assertNull(vm.pendingFocus.value)
    }

    @Test
    fun `stashRouteSelection parks the current route`() {
        val vm = SelectedFeatureViewModel(app)
        assertNull(vm.inactiveNavigation.value)
        val route = SpecificFeature.Route(waypoints = listOf(place()))
        vm.set(route)
        vm.stashRouteSelection()
        assertEquals(route, vm.inactiveNavigation.value)
    }

    @Test
    fun `stashRouteSelection ignores non-route selections`() {
        val vm = SelectedFeatureViewModel(app)
        vm.set(place())
        vm.stashRouteSelection()
        assertNull(vm.inactiveNavigation.value)
    }

    @Test
    fun `setInactiveNavigation replaces the parked route`() {
        val vm = SelectedFeatureViewModel(app)
        val route = SpecificFeature.Route(waypoints = listOf(place()))
        vm.setInactiveNavigation(route)
        assertEquals(route, vm.inactiveNavigation.value)
        vm.setInactiveNavigation(null)
        assertNull(vm.inactiveNavigation.value)
    }

    // --- SavedPlacesViewModel ---

    @Test
    fun `added places persist and read back saved`() {
        val vm = SavedPlacesViewModel(app)
        vm.addSaved(place())
        val saved = awaitState(vm.saved) { it.any { p -> p.name == "Ferry Building" } }
        assertTrue(vm.isSaved(place()))
        assertEquals(1, saved.size)
    }

    @Test
    fun `adding twice does not duplicate`() {
        val vm = SavedPlacesViewModel(app)
        vm.addSaved(place())
        awaitState(vm.saved) { it.isNotEmpty() }
        vm.addSaved(place())
        Thread.sleep(300)
        assertEquals(1, vm.saved.value.size)
    }

    @Test
    fun `removed places read back unsaved`() {
        val vm = SavedPlacesViewModel(app)
        vm.addSaved(place())
        awaitState(vm.saved) { it.isNotEmpty() }
        vm.removeSaved(SavedPlace.from(place()))
        awaitEquals(vm.saved, emptyList())
        assertFalse(vm.isSaved(place()))
    }

    @Test
    fun `blank renames are dropped and real renames apply`() {
        val vm = SavedPlacesViewModel(app)
        vm.addSaved(place())
        val stored = awaitState(vm.saved) { it.isNotEmpty() }.first()
        vm.renameSaved(stored, "   ")
        Thread.sleep(300)
        assertEquals("Ferry Building", vm.saved.value.first().name)
        vm.renameSaved(stored, "Ferry Bldg")
        awaitEquals(vm.saved, listOf(stored.copy(name = "Ferry Bldg")))
    }

    @Test
    fun `named lists create fill and delete`() {
        val vm = SavedPlacesViewModel(app)
        vm.createList("Trip")
        awaitState(vm.lists) { it.containsKey("Trip") }
        vm.createList("  ")
        vm.createList("Trip")
        Thread.sleep(200)
        assertEquals(listOf("Trip"), vm.lists.value.keys.toList())
        vm.addToList("Trip", place())
        awaitState(vm.lists) { it["Trip"]?.isNotEmpty() == true }
        vm.removeFromList("Trip", SavedPlace.from(place()))
        awaitState(vm.lists) { it["Trip"]?.isEmpty() == true }
        vm.deleteList("Trip")
        awaitEquals(vm.lists, emptyMap())
    }

    // --- ParkingViewModel ---

    @Test
    fun `saveParking publishes the active spot`() {
        val vm = ParkingViewModel(app)
        assertNull(vm.active.value)
        vm.saveParking(37.8, -122.4, note = "P2")
        val active = awaitState(vm.active) { it != null }
        assertEquals(37.8, active?.lat)
        assertEquals(-122.4, active?.lon)
        assertEquals("P2", active?.note)
    }

    @Test
    fun `saving again archives the previous spot`() {
        val vm = ParkingViewModel(app)
        vm.saveParking(1.0, 2.0)
        awaitState(vm.active) { it != null }
        vm.saveParking(3.0, 4.0)
        awaitState(vm.active) { it?.lat == 3.0 }
        val history = awaitState(vm.history) { it.isNotEmpty() }
        assertEquals(1.0, history.first().lat)
    }

    @Test
    fun `updateNote keeps the spot in place`() {
        val vm = ParkingViewModel(app)
        vm.saveParking(1.0, 2.0)
        awaitState(vm.active) { it != null }
        vm.updateNote("level 3")
        awaitState(vm.active) { it?.note == "level 3" }
        assertTrue(vm.history.value.isEmpty())
        vm.updateNote("   ")
        awaitState(vm.active) { it?.note == null }
    }

    @Test
    fun `clear archives and empties the active spot`() {
        val vm = ParkingViewModel(app)
        vm.saveParking(1.0, 2.0)
        awaitState(vm.active) { it != null }
        vm.clear()
        awaitState(vm.active) { it == null }
        awaitState(vm.history) { it.isNotEmpty() }
    }

    // --- MapsSearchViewModel: synchronous surface ---

    @Test
    fun `short queries clear without searching`() {
        val vm = MapsSearchViewModel(app)
        vm.setQuery("x", 0.0, 0.0)
        assertEquals("x", vm.query.value)
        assertTrue(vm.results.value.isEmpty())
        assertFalse(vm.searching.value)
    }

    @Test
    fun `reset clears the query and results`() {
        val vm = MapsSearchViewModel(app)
        vm.setQuery("abc", 0.0, 0.0)
        assertTrue(vm.searching.value)
        vm.reset()
        assertEquals("", vm.query.value)
        assertTrue(vm.results.value.isEmpty())
    }

    @Test
    fun `searchAndSelectFirst resolves null with no index`() {
        val vm = MapsSearchViewModel(app)
        var called = false
        vm.searchAndSelectFirst("pizza", 37.8, -122.4) { first ->
            called = true
            assertNull(first)
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (!called && System.currentTimeMillis() < deadline) {
            pumpMain()
            Thread.sleep(25)
        }
        pumpMain()
        assertTrue(called)
        assertTrue(vm.results.value.isEmpty())
    }

    @Test
    fun `recordRecent and clearRecents round-trip through the store`() {
        val vm = MapsSearchViewModel(app)
        // `recents` shares WhileSubscribed: without a collector the sharing
        // never starts and the value freezes at its initial. A background
        // collector keeps it alive; pumpMain() drains the Main-side updates.
        val keeper = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            .launch { vm.recents.collect {} }
        try {
            vm.recordRecent("  pizza  ")
            awaitState(vm.recents) { it.contains("pizza") }
            assertEquals(listOf("pizza"), vm.recents.value)
            vm.recordRecent("   ")
            Thread.sleep(200)
            assertEquals(listOf("pizza"), vm.recents.value)
            vm.clearRecents()
            awaitEquals(vm.recents, emptyList())
        } finally {
            keeper.cancel()
        }
    }

    // --- MapSettingsViewModel ---

    @Test
    fun `theme mode persists through the settings vm`() {
        val vm = MapSettingsViewModel(app)
        vm.setThemeMode(ThemeMode.DARK)
        awaitEquals(vm.themeMode, ThemeMode.DARK)
        assertEquals(false, ThemeMode.LIGHT.darkOverride)
        assertEquals(true, ThemeMode.DARK.darkOverride)
        assertEquals(null, ThemeMode.SYSTEM.darkOverride)
        assertEquals(ThemeMode.SYSTEM, ThemeMode.from("bogus"))
    }

    @Test
    fun `layer toggles persist through the settings vm`() {
        val vm = MapSettingsViewModel(app)
        vm.setTrafficLayer(false)
        awaitEquals(vm.trafficLayer, false)
        vm.setSatelliteLayer(true)
        awaitEquals(vm.satelliteLayer, true)
        vm.setGoogleServices(true)
        awaitEquals(vm.googleServices, true)
        vm.setVoiceGuidance(false)
        awaitEquals(vm.voiceGuidance, false)
    }

    // --- TransitStopsViewModel: synchronous board surface ---

    @Test
    fun `board starts idle and opens and closes`() {
        val vm = TransitStopsViewModel(app)
        assertEquals(DeparturesState.Idle, vm.departures.value)
        val stop = com.vayunmathur.maps.data.transit.TransitStop(
            id = "s1", name = "Central", lat = 1.0, lon = 2.0,
        )
        vm.openStop(stop)
        assertEquals(stop, vm.selected.value)
        vm.refresh()
        vm.closeStop()
        assertNull(vm.selected.value)
    }

    @Test
    fun `refresh without a stop is a no-op`() {
        val vm = TransitStopsViewModel(app)
        vm.refresh()
        assertEquals(DeparturesState.Idle, vm.departures.value)
    }

    @Test
    fun `trip sheet opens and closes`() {
        val vm = TransitStopsViewModel(app)
        assertEquals(TripItineraryState.Idle, vm.tripItinerary.value)
        vm.closeTrip()
        assertNull(vm.selectedTrip.value)
        assertEquals(TripItineraryState.Idle, vm.tripItinerary.value)
    }
}
