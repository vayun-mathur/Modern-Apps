package com.vayunmathur.findfamily.util
import com.vayunmathur.findfamily.platform.MapPageState

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.library.map.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Robolectric tests for [MapPageState]'s synchronous history/form logic.
 *
 * Selection is not covered here on purpose: which person/place is selected
 * lives in the navigation back stack (`Route.PersonSheet` /
 * `Route.WaypointSheet`), and route-level navigation tests cover it.
 * The setter paths (including the history-pin clearing rules added for
 * GitHub #818) never touch the repository; [MapPageState.saveCurrentWaypoint]'s
 * invalid-form no-ops return before any database access.
 */
@RunWith(AndroidJUnit4::class)
class W5MapPageStateTest {

    private lateinit var state: MapPageState

    @BeforeTest
    fun create() {
        state = MapPageState(
            CoroutineScope(Dispatchers.Unconfined),
            FindFamilyRepository.get(ApplicationProvider.getApplicationContext()),
        )
    }

    @Test
    fun initialStateIsEmpty() {
        assertTrue(state.isShowingPresent.value)
        assertNull(state.historicalPosition.value)
        assertEquals("", state.waypointName.value)
    }

    @Test
    fun openingPersonDropsStaleHistoricalPin() {
        state.setShowingPresent(false)
        state.setHistoricalPosition(GeoPoint(3.0, 4.0))
        state.onPersonOpened()
        assertTrue(state.isShowingPresent.value)
        assertNull(state.historicalPosition.value)
    }

    @Test
    fun returningToPresentDropsHistoricalPin() {
        state.setShowingPresent(false)
        state.setHistoricalPosition(GeoPoint(3.0, 4.0))
        state.setShowingPresent(true)
        assertNull(state.historicalPosition.value)
        // Staying in history keeps the pin.
        state.setShowingPresent(false)
        state.setHistoricalPosition(GeoPoint(3.0, 4.0))
        assertEquals(GeoPoint(3.0, 4.0), state.historicalPosition.value)
    }

    @Test
    fun beginCreateWaypointResetsForm() {
        state.setWaypointName("old")
        state.beginCreateWaypoint()
        assertEquals("", state.waypointName.value)
        assertEquals("100", state.waypointRange.value)
        assertEquals(Coord(0.0, 0.0), state.waypointCoord.value)
    }

    @Test
    fun beginEditWaypointPrefillsForm() {
        state.beginEditWaypoint(Waypoint("home", 250.0, Coord(1.0, 2.0), id = 11L))
        assertEquals("home", state.waypointName.value)
        assertEquals("250.0", state.waypointRange.value)
        assertEquals(Coord(1.0, 2.0), state.waypointCoord.value)
    }

    @Test
    fun saveCurrentWaypointNoOpsOnInvalidForm() {
        // Blank name: returns false before touching the repository.
        state.beginCreateWaypoint()
        state.setWaypointName("")
        state.setWaypointRange("100")
        assertFalse(state.saveCurrentWaypoint(0L))
        // Unparseable range: same silent no-op.
        state.setWaypointName("home")
        state.setWaypointRange("not-a-number")
        assertFalse(state.saveCurrentWaypoint(0L))
    }

    @Test
    fun waypointFormSettersRoundTrip() {
        state.setWaypointName("cabin")
        state.setWaypointRange("50")
        state.setWaypointCoord(Coord(5.0, 6.0))
        assertEquals("cabin", state.waypointName.value)
        assertEquals("50", state.waypointRange.value)
        assertEquals(Coord(5.0, 6.0), state.waypointCoord.value)
    }
}
