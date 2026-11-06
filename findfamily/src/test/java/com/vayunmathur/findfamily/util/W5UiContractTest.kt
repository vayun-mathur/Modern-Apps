package com.vayunmathur.findfamily.util

import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.toGeoPoint
import com.vayunmathur.findfamily.data.toPosition
import com.vayunmathur.findfamily.ui.dialogs.decodeBase26
import com.vayunmathur.findfamily.ui.dialogs.encodeBase26
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the map-page UI contract ([MainPageUiState] derived flags
 * and action-interface no-op defaults) plus `Long.encodeBase26` /
 * `String.decodeBase26` round-trips from AddPersonDialog.
 */
class W5UiContractTest {

    private fun user(id: Long) = User(
        name = "u$id",
        photo = null,
        locationName = "loc",
        sendingEnabled = true,
        requestStatus = RequestStatus.MUTUAL_CONNECTION,
        id = id,
    )

    @Test
    fun historyModeNeedsSelectedUserAndHistoryView() {
        assertFalse(MainPageUiState().historyMode)
        assertFalse(MainPageUiState(selectedUserId = 1L, isShowingPresent = true).historyMode)
        assertTrue(MainPageUiState(selectedUserId = 1L, isShowingPresent = false).historyMode)
        // A waypoint selection alone is not history mode.
        assertFalse(MainPageUiState(selectedWaypointId = 2L, isShowingPresent = false).historyMode)
    }

    @Test
    fun isSelfSelectedComparesIds() {
        assertTrue(MainPageUiState(selectedUserId = 5L, selfUserId = 5L).isSelfSelected)
        assertFalse(MainPageUiState(selectedUserId = 6L, selfUserId = 5L).isSelfSelected)
        assertFalse(MainPageUiState(selfUserId = 5L).isSelfSelected)
    }

    @Test
    fun nothingSelectedNeedsBothNull() {
        assertTrue(MainPageUiState().nothingSelected)
        assertFalse(MainPageUiState(selectedUserId = 1L).nothingSelected)
        assertFalse(MainPageUiState(selectedWaypointId = 1L).nothingSelected)
    }

    @Test
    fun familyListNoopActionsDoNotThrow() {
        val noop = FamilyListActions.Noop
        noop.selectUser(1L)
        noop.acceptRequest(1L)
        noop.beginEditWaypoint(Waypoint.NEW_WAYPOINT)
    }

    @Test
    fun personAndMainPageNoopActionsDoNotThrow() {
        val u = user(1L)
        val person = PersonActions.Noop
        person.setUserSharing(u, false)
        person.setUserAutoToggle(u, null)
        person.setUserArrivalToggle(u, null)
        person.setCrowdFinding(true)
        val main = MainPageActions.Noop
        main.clearSelection()
        main.setShowingPresent(true)
        main.beginCreateWaypoint()
        main.enterHistory()
        main.setWaypointName("home")
        main.setWaypointRange("100")
    }

    @Test
    fun personStateDefaults() {
        val state = PersonUiState(user = user(1L))
        assertNull(state.location)
        assertTrue(state.sharingGloballyEnabled)
        assertFalse(state.crowdFindingEnabled)
        assertTrue(state.connectedUsers.isEmpty())
    }

    // --- base26 codec (user-id text field <-> Long) ---

    @Test
    fun base26RoundTrips() {
        for (id in listOf(1L, 2L, 25L, 26L, 27L, 123456789L, Long.MAX_VALUE / 2)) {
            assertEquals(id, id.encodeBase26().decodeBase26(), "id=$id")
        }
    }

    @Test
    fun base26UsesOnlyUppercaseLetters() {
        val encoded = 987654321L.encodeBase26()
        assertTrue(encoded.isNotEmpty())
        assertTrue(encoded.all { it in 'A'..'Z' })
    }

    @Test
    fun base26SingleLetterValues() {
        // Digits are value+'A', most-significant first: 0 encodes to empty
        // (while-loop never runs), 26 is two digits [1,0] -> "BA".
        assertEquals("", 0L.encodeBase26())
        assertEquals("B", 1L.encodeBase26())
        assertEquals("BA", 26L.encodeBase26())
    }

    @Test
    fun coordToGeoPointKeepsLonLatOrder() {
        // GeoPoint is (longitude, latitude) — the easy mistake is swapping them.
        val point = Coord(lat = 10.0, lon = 20.0).toGeoPoint()
        assertEquals(20.0, point.longitude)
        assertEquals(10.0, point.latitude)
        // toPosition is the deprecated alias for toGeoPoint.
        assertEquals(point, Coord(lat = 10.0, lon = 20.0).toPosition())
    }
}
