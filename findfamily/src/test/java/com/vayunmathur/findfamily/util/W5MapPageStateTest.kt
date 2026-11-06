package com.vayunmathur.findfamily.util

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
 * Robolectric tests for [MapPageState]'s synchronous selection/form logic.
 *
 * The setter paths (including the history-pin clearing rules added for
 * GitHub #818) never touch the repository, so they run against the real
 * singleton; [saveCurrentWaypoint]'s invalid-form no-ops return before any
 * database access.
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
        assertNull(state.selectedUserId.value)
        assertNull(state.selectedWaypointId.value)
        assertTrue(state.isShowingPresent.value)
        assertNull(state.historicalPosition.value)
        assertEquals("", state.waypointName.value)
    }

    @Test
    fun selectingUserClearsWaypointAndHistoryPin() {
        state.setSelectedWaypointId(9L)
        state.setHistoricalPosition(GeoPoint(1.0, 2.0))
        state.selectUser(5L)
        assertEquals(5L, state.selectedUserId.value)
        assertNull(state.selectedWaypointId.value)
        assertTrue(state.isShowingPresent.value)
        assertNull(state.historicalPosition.value)
    }

    @Test
    fun setSelectedUserIdDropsStaleHistoricalPin() {
        state.setHistoricalPosition(GeoPoint(3.0, 4.0))
        state.setSelectedUserId(6L)
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
    fun clearSelectionResetsIdsAndPin() {
        state.selectUser(1L)
        state.setSelectedWaypointId(2L)
        state.setHistoricalPosition(GeoPoint(0.0, 0.0))
        state.clearSelection()
        assertNull(state.selectedUserId.value)
        assertNull(state.selectedWaypointId.value)
        assertNull(state.historicalPosition.value)
    }

    @Test
    fun applyInitialSelectionSetsBothIds() {
        state.applyInitialSelection(initialUserId = 7L, initialWaypointId = 8L)
        assertEquals(7L, state.selectedUserId.value)
        assertEquals(8L, state.selectedWaypointId.value)
    }

    @Test
    fun beginCreateWaypointResetsForm() {
        state.setWaypointName("old")
        state.beginCreateWaypoint()
        assertEquals(0L, state.selectedWaypointId.value)
        assertEquals("", state.waypointName.value)
        assertEquals("100", state.waypointRange.value)
        assertEquals(Coord(0.0, 0.0), state.waypointCoord.value)
    }

    @Test
    fun beginEditWaypointPrefillsForm() {
        state.beginEditWaypoint(Waypoint("home", 250.0, Coord(1.0, 2.0), id = 11L))
        assertEquals(11L, state.selectedWaypointId.value)
        assertEquals("home", state.waypointName.value)
        assertEquals("250.0", state.waypointRange.value)
        assertEquals(Coord(1.0, 2.0), state.waypointCoord.value)
    }

    @Test
    fun saveCurrentWaypointNoOpsOnInvalidForm() {
        // Blank name: returns before touching the repository.
        state.beginCreateWaypoint()
        state.setWaypointName("")
        state.setWaypointRange("100")
        state.saveCurrentWaypoint()
        assertEquals(0L, state.selectedWaypointId.value)
        // Unparseable range: same silent no-op.
        state.setWaypointName("home")
        state.setWaypointRange("not-a-number")
        state.saveCurrentWaypoint()
        assertEquals(0L, state.selectedWaypointId.value)
    }

    @Test
    fun waypointFormSettersRoundTrip() {
        state.setWaypointName("cabin")
        state.setWaypointRange("50")
        state.setWaypointCoord(Coord(5.0, 6.0))
        assertEquals("cabin", state.waypointName.value)
        assertEquals("50", state.waypointRange.value)
        assertEquals(Coord(5.0, 6.0), state.waypointCoord.value)
        assertFalse(state.selectedUserId.value != null)
    }
}
